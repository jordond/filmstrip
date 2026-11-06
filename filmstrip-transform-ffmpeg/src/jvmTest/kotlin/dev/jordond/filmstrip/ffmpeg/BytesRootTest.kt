package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.freshPrivateRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Where a buffer goes when the directory named for the user is refused, which another user can force by getting there
 * first.
 */
class BytesRootTest {
  private val scratch = Files.createTempDirectory("bytes-root-test").toFile()
  private val source = MediaSource.Bytes(byteArrayOf(1, 2, 3))

  @AfterTest
  fun removeScratch() {
    scratch.deleteRecursively()
  }

  private fun modeOf(path: File): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(path.toPath(), LinkOption.NOFOLLOW_LINKS))

  private fun reachable(name: String): File =
    File(scratch, name).also {
      it.mkdirs()
      Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString(REACHABLE))
    }

  private fun planted(name: String): List<File> =
    listOf(
      reachable(name),
      File(scratch, "$name-file").also { it.writeBytes(byteArrayOf(0)) },
      File(scratch, "$name-link").also { link ->
        Files.createSymbolicLink(link.toPath(), reachable("$name-target").toPath())
      },
    )

  @Test
  fun `a root that is not refused holds the file and the fallback is never made`() {
    val named = File(scratch, "named")

    val path = BytesRoot(named.path) { error("nothing should need the fallback") }.fileFor(source)

    path shouldStartWith named.path
  }

  @Test
  fun `a planted root falls back on a fresh private directory and stays as it was`() {
    planted("victim").forEach { root ->
      val fallback = freshPrivateRoot()
      val before = if (root.isDirectory) root.list().orEmpty().toList() else emptyList()

      val path = File(BytesRoot(root.path) { fallback }.fileFor(source))

      path.path shouldStartWith fallback
      path.readBytes().contentEquals(source.bytes).shouldBeTrue()
      modeOf(File(fallback)) shouldBe OWNER_ONLY
      modeOf(path.parentFile) shouldBe OWNER_ONLY
      if (root.isDirectory) root.list().orEmpty().toList() shouldBe before
    }
  }

  @Test
  fun `one refused root keeps one path and one fallback across calls`() {
    var made = 0
    val root =
      BytesRoot(reachable("victim").path) {
        made++
        freshPrivateRoot()
      }

    val first = root.fileFor(source)

    root.fileFor(source) shouldBe first
    made shouldBe 1
    File(first)
      .parentFile.parentFile.parentFile.name shouldStartWith "filmstrip-"
  }

  @Test
  fun `a later refusal stays on the fallback even if the named root comes good`() {
    val victim = reachable("victim")
    val fallback = freshPrivateRoot()
    val root = BytesRoot(victim.path) { fallback }

    val first = root.fileFor(source)
    Files.setPosixFilePermissions(victim.toPath(), PosixFilePermissions.fromString(OWNER_ONLY))

    root.fileFor(source) shouldBe first
  }

  @Test
  fun `a fallback that is refused fails as a write failure`() {
    val fallback = reachable("fallback")

    val failure =
      shouldThrow<MediaBytesFileFailure> { BytesRoot(reachable("victim").path) { fallback.path }.fileFor(source) }

    failure.message shouldContain "other users"
    fallback
      .list()
      .orEmpty()
      .toList()
      .shouldBeEmpty()
  }

  @Test
  fun `a fallback that cannot be made reports the refusal of the named root`() {
    val victim = reachable("victim")

    val failure =
      shouldThrow<MediaBytesFileFailure> {
        BytesRoot(victim.path) { throw java.io.IOException("no room") }.fileFor(source)
      }

    failure.message shouldContain victim.path
    failure.suppressed.single().message shouldBe "no room"
  }

  @Test
  fun `a root with no fallback fails on the refusal`() {
    shouldThrow<MediaBytesFileFailure> { BytesRoot(reachable("victim").path).fileFor(source) }
  }

  @Test
  fun `the default root falls back when planted and the fallback is gone once the JVM exits`() {
    val temporary = File(scratch, "tmp").also { it.mkdirs() }
    val planted = File(temporary, "filmstrip-${System.getProperty("user.name")}")
    planted.mkdirs()
    Files.setPosixFilePermissions(planted.toPath(), PosixFilePermissions.fromString(REACHABLE))

    val written = File(writtenByOtherJvm(temporary))

    written.path shouldStartWith temporary.path
    written.path shouldNotContain planted.path + File.separator
    written.exists().shouldBeFalse()
    temporary.list().orEmpty().toList() shouldBe listOf(planted.name)
    planted
      .list()
      .orEmpty()
      .toList()
      .shouldBeEmpty()
    modeOf(planted) shouldBe REACHABLE
  }

  @Test
  fun `the default root stays where it is named when nobody planted it`() {
    val temporary = File(scratch, "tmp").also { it.mkdirs() }
    val named = File(temporary, "filmstrip-${System.getProperty("user.name")}")

    val written = File(writtenByOtherJvm(temporary))

    written.path shouldStartWith named.path + File.separator
    written.exists().shouldBeTrue()
    temporary.list().orEmpty().toList() shouldBe listOf(named.name)
  }

  // The default root is one value for the process, so its own JVM is the only way to plant it first and see it exit.
  private fun writtenByOtherJvm(temporary: File): String {
    val process =
      ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").path,
        "-Djava.io.tmpdir=${temporary.path}",
        "-cp",
        System.getProperty("java.class.path"),
        WritesBufferAndExits::class.java.name,
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val output =
      process.inputStream
        .bufferedReader()
        .readText()
        .trim()
    process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS).shouldBeTrue()
    process.exitValue() shouldBe 0
    return output
  }

  private companion object {
    const val OWNER_ONLY = "rwx------"
    const val REACHABLE = "rwxr-xr-x"
    const val CHILD_SECONDS = 60L
  }
}

/**
 * Writes a buffer through the default root, checks the file is there and prints its path.
 */
internal object WritesBufferAndExits {
  @JvmStatic
  fun main(args: Array<String>) {
    val path = checkNotNull(readablePath(MediaSource.Bytes(byteArrayOf(4, 5, 6))))
    check(File(path).isFile)
    println(path)
  }
}
