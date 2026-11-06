package dev.jordond.filmstrip.convention.testmedia

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DownloadTestMediaTaskTest {
  private val root = Files.createTempDirectory("download").toFile()

  private val cache = root.resolve("cache")

  private val output = root.resolve("out")

  private val bytes = "a clip".toByteArray()

  private val entry = TestMediaEntry("clip.mp4", sha256(bytes), bytes.size.toLong())

  private val requests = AtomicInteger()

  private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

  @AfterTest
  fun stop() {
    server.stop(0)
  }

  @Test
  fun `a fetch with the right digest lands in the cache and the fixture directory`() {
    serve { exchange -> exchange.reply(200, bytes) }

    task().download()

    cache.resolve("${entry.sha256}.mp4").readBytes() shouldBe bytes
    output.resolve("clip.mp4").readBytes() shouldBe bytes
  }

  @Test
  fun `a 503 is retried`() {
    serve { exchange -> if (requests.get() == 1) exchange.reply(503) else exchange.reply(200, bytes) }

    task().download()

    requests.get() shouldBe 2
    output.resolve("clip.mp4").readBytes() shouldBe bytes
  }

  @Test
  fun `a 404 fails at once naming the url`() {
    serve { exchange -> exchange.reply(404) }

    val error = assertFailsWith<GradleException> { task().download() }

    requests.get() shouldBe 1
    error.message shouldContain "${baseUrl()}/${TestMediaManifest.keyFor(entry)}"
    error.message shouldContain "HTTP 404"
  }

  @Test
  fun `bytes with the wrong digest fail naming both digests`() {
    val wrong = "not the clip".toByteArray()
    serve { exchange -> exchange.reply(200, wrong) }

    val error = assertFailsWith<GradleException> { task().download() }

    error.message shouldContain sha256(wrong)
    error.message shouldContain entry.sha256
  }

  @Test
  fun `a copy another build cached rescues a failed fetch`() {
    serve { exchange ->
      cache.resolve("${entry.sha256}.mp4").writeBytes(bytes)
      exchange.reply(404)
    }

    task().download()

    requests.get() shouldBe 1
    output.resolve("clip.mp4").readBytes() shouldBe bytes
  }

  private fun serve(handler: (HttpExchange) -> Unit) {
    server.createContext("/") { exchange ->
      requests.incrementAndGet()
      try {
        handler(exchange)
      } finally {
        exchange.close()
      }
    }
    server.start()
  }

  private fun baseUrl(): String = "http://${server.address.hostString}:${server.address.port}"

  private fun task(): DownloadTestMediaTask {
    val manifest =
      root.resolve(TestMediaManifest.FILE_NAME).also {
        TestMediaManifest.write(it, listOf(entry), build = null)
      }
    val project = ProjectBuilder.builder().withProjectDir(root.resolve("project")).build()
    return project.tasks.register("download", DownloadTestMediaTask::class.java).get().apply {
      this.manifest.set(manifest)
      outputDirectory.set(output)
      cacheDirectory.set(cache)
      baseUrl.set(baseUrl())
      retryBackoffMillis.set(0L)
    }
  }
}

private fun HttpExchange.reply(
  status: Int,
  body: ByteArray? = null,
) {
  sendResponseHeaders(status, body?.size?.toLong() ?: -1)
  body?.let { responseBody.write(it) }
}

private fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
