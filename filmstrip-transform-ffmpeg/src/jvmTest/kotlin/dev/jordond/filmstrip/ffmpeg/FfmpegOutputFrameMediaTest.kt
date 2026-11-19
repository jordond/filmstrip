package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.RED
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.pixelAt
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exports under an [OutputFrame], read back from the written file.
 */
class FfmpegOutputFrameMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a fixed frame writes the same size whichever clip comes first`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      for (portraitFirst in listOf(false, true)) {
        val first = if (portraitFirst) fixture.portrait else fixture.landscape
        val second = if (portraitFirst) fixture.landscape else fixture.portrait
        val composition =
          compositionOf {
            clip(MediaSource.of(first.absolutePath)) { trim(0.seconds, 1.seconds) }
            clip(MediaSource.of(second.absolutePath)) { trim(0.seconds, 1.seconds) }
            fill(Fill.Solid(RED))
            frame(OutputFrame.Fixed(FIXED))
          }
        val output = File.createTempFile("filmstrip-frame-fixed", ".mp4").also { it.delete() }

        val info = fixture.exported(fixture.plannedRun(composition, ExportSpec()), output)

        withClue("portrait first: $portraitFirst") {
          info.video.shouldNotBeNull().displaySize shouldBe FIXED
          val portraitAt = if (portraitFirst) PORTRAIT_READ else PORTRAIT_READ + 1.seconds
          assertReddish(pixelAt(output.absolutePath, 2, FIXED.height / 2, portraitAt))
        }
        output.delete()
      }
    }

  @Test
  fun `an aspect frame writes the first clip's height at that aspect`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val source = MediaSource.of(fixture.landscape.absolutePath)
      val height =
        fixture.filmstrip
          .probe(source)
          .shouldBeInstanceOf<ProbeResult.Success>()
          .info.video
          .shouldNotBeNull()
          .displaySize.height
      val composition =
        compositionOf {
          clip(source) { trim(0.seconds, 1.seconds) }
          frame(OutputFrame.Aspect(AspectRatio.Square))
        }
      val output = File.createTempFile("filmstrip-frame-aspect", ".mp4").also { it.delete() }

      val info = fixture.exported(fixture.plannedRun(composition, ExportSpec()), output)
      output.delete()

      info.video.shouldNotBeNull().displaySize shouldBe Size(height, height)
    }

  private fun assertReddish(pixel: Triple<Int, Int, Int>) {
    val (red, green, blue) = pixel
    withClue("expected the fill's red in the bar, got $pixel") { (red > 200 && green < 60 && blue < 60).shouldBeTrue() }
  }

  private companion object {
    val FIXED = Size(640, 360)

    // Well inside the portrait clip's second, clear of the cut on either side.
    val PORTRAIT_READ = 500.milliseconds
  }
}
