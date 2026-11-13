package dev.jordond.filmstrip.ffmpeg.bundled

import dev.jordond.filmstrip.ffmpeg.FfmpegBinariesResult
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Where the carried binaries come from per platform, against directories standing in for javacpp's cache.
 */
class BundledBinariesTest {
  private val scratch = Files.createTempDirectory("filmstrip-bundled-binaries").toFile()

  @AfterTest
  fun delete() {
    scratch.deleteRecursively()
  }

  @Test
  fun `every platform named has an ffmpeg and an ffprobe on the classpath`() {
    for (platform in BundledBinaries.PLATFORMS) {
      val suffix = if (platform.startsWith("windows")) ".exe" else ""
      for (tool in listOf("ffmpeg", "ffprobe")) {
        withClue("$platform $tool") {
          javaClass.getResource("${BundledBinaries.RESOURCE_ROOT}/$platform/$tool$suffix").shouldNotBeNull()
        }
      }
    }
  }

  @Test
  fun `a platform with no binary is refused naming it and the ones carried`() =
    runTest {
      val result = BundledBinaries(platform = "plan9-mips").locate()

      val message = result.shouldBeInstanceOf<FfmpegBinariesResult.Unavailable>().message
      message shouldContain "plan9-mips"
      message shouldContain System.getProperty("os.name")
      message shouldContain System.getProperty("os.arch")
      BundledBinaries.PLATFORMS.forEach { message shouldContain it }
      message shouldContain "FfmpegConfig.executablePath"
    }

  @Test
  fun `the binaries are marked executable where they were unpacked`() =
    runTest {
      val unpacked = directory("linux-x86_64")

      val found = BundledBinaries("linux-x86_64") { unpacked.takeIf { _ -> it == "linux-x86_64" } }.locate()

      val binaries = found.shouldBeInstanceOf<FfmpegBinariesResult.Found>()
      binaries.ffmpeg shouldBe File(unpacked, "ffmpeg").absolutePath
      binaries.ffprobe shouldBe File(unpacked, "ffprobe").absolutePath
      File(binaries.ffmpeg).canExecute() shouldBe true
      File(binaries.ffprobe).canExecute() shouldBe true
    }

  @Test
  fun `a gpl build on the classpath is used ahead of the lgpl one`() =
    runTest {
      val directories = listOf("linux-x86_64", "linux-x86_64-gpl").associateWith(::directory)

      val found = BundledBinaries("linux-x86_64") { directories[it] }.locate()

      found.shouldBeInstanceOf<FfmpegBinariesResult.Found>().ffmpeg shouldBe
        File(directories.getValue("linux-x86_64-gpl"), "ffmpeg").absolutePath
    }

  @Test
  fun `windows binaries carry their extension`() =
    runTest {
      val unpacked = directory("windows-x86_64", suffix = ".exe")

      val found = BundledBinaries("windows-x86_64") { unpacked.takeIf { _ -> it == "windows-x86_64" } }.locate()

      found.shouldBeInstanceOf<FfmpegBinariesResult.Found>().ffmpeg shouldBe File(unpacked, "ffmpeg.exe").absolutePath
    }

  @Test
  fun `a cache that cannot be written is refused with its reason`() =
    runTest {
      val result = BundledBinaries("linux-x86_64") { throw IOException("No space left on device") }.locate()

      val message = result.shouldBeInstanceOf<FfmpegBinariesResult.Unavailable>().message
      message shouldContain "could not be unpacked"
      message shouldContain "No space left on device"
    }

  private fun directory(
    name: String,
    suffix: String = "",
  ): File =
    File(scratch, name).apply {
      mkdirs()
      File(this, "ffmpeg$suffix").writeText("")
      File(this, "ffprobe$suffix").writeText("")
    }
}
