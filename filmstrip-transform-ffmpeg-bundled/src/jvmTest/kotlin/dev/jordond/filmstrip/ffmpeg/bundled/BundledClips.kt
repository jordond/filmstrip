package dev.jordond.filmstrip.ffmpeg.bundled

import dev.jordond.filmstrip.ffmpeg.FfmpegBinariesResult
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

internal const val CLIP_WIDTH = 320
internal const val CLIP_HEIGHT = 240

internal val IS_MAC: Boolean = System.getProperty("os.name").startsWith("Mac")

// Writes one second of moving test pattern and a tone into [directory] with the carried ffmpeg itself, through the
// encoders every build of it has, so the tests need no fixture another ffmpeg made.
internal suspend fun bundledClip(directory: File): File {
  val ffmpeg =
    BundledBinaries.Default
      .locate()
      .shouldBeInstanceOf<FfmpegBinariesResult.Found>()
      .ffmpeg
  val clip = File(directory, "clip.mp4")
  val log = File(directory, "clip.log")
  val command =
    listOf(
      ffmpeg,
      "-hide_banner",
      "-loglevel",
      "error",
      "-y",
      "-f",
      "lavfi",
      "-i",
      "testsrc2=s=${CLIP_WIDTH}x$CLIP_HEIGHT:r=30:d=1",
      "-f",
      "lavfi",
      "-i",
      "sine=f=440:d=1:r=48000",
      "-c:v",
      "libopenh264",
      "-pix_fmt",
      "yuv420p",
      "-c:a",
      "aac",
      "-shortest",
      clip.absolutePath,
    )
  val exit =
    ProcessBuilder(command)
      .redirectErrorStream(true)
      .redirectOutput(log)
      .start()
      .waitFor()
  withClue(log.readText()) { exit shouldBe 0 }
  return clip
}
