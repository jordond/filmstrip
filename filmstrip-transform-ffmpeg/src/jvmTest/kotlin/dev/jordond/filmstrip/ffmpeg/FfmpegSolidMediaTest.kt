package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.PRIMARY_HZ
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.assertOnTheGrid
import dev.jordond.filmstrip.ffmpeg.support.assertReads
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.decodedAudio
import dev.jordond.filmstrip.ffmpeg.support.indexedClip
import dev.jordond.filmstrip.ffmpeg.support.levelAt
import dev.jordond.filmstrip.ffmpeg.support.toneAt
import dev.jordond.filmstrip.ffmpeg.support.writtenFrames
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Solid colours exported by ffmpeg, read back from the written file.
 */
class FfmpegSolidMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a solid either side of a clip holds its colour for its span with silence under it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val source = indexedClip(RATE, 1.seconds, blue = CLIP_BLUE, sound = 1.seconds)
      val composition =
        compositionOf {
          solid(AuthoredColors.MID_GREY, 1.seconds)
          clip(MediaSource.of(source.absolutePath))
          solid(AuthoredColors.MID_GREEN, 1.seconds)
        }
      val output = File.createTempFile("filmstrip-solid", ".mp4").also { it.delete() }

      val plan = fixture.plannedRun(composition, ExportSpec(frameRate = RATE))
      fixture.exported(plan, output)

      val path = output.absolutePath
      val frames = writtenFrames(path, middleOnly = true)
      assertOnTheGrid(frames, 3L * RATE, RATE, "three one second spans")
      assertReads(frames.take(RATE), channelsOf(AuthoredColors.MID_GREY), "the leading solid", TOLERANCE)
      withClue("the clip's own frames in the middle span") {
        frames.slice(RATE until 2 * RATE).all { it.blue > 150 }.shouldBeTrue()
      }
      assertReads(frames.drop(2 * RATE), channelsOf(AuthoredColors.MID_GREEN), "the trailing solid", TOLERANCE)
      assertReads(
        writtenFrames(path, probe = CORNER).take(RATE),
        channelsOf(AuthoredColors.MID_GREY),
        "a corner",
        TOLERANCE,
      )

      val audio = decodedAudio(path)
      withClue("silence under the leading solid") { (audio.levelAt(500.milliseconds) < SILENT).shouldBeTrue() }
      withClue(
        "the clip's tone in its span",
      ) { (audio.toneAt(1_500.milliseconds, PRIMARY_HZ) > AUDIBLE).shouldBeTrue() }
      withClue("silence under the trailing solid") { (audio.levelAt(2_500.milliseconds) < SILENT).shouldBeTrue() }

      output.delete()
      source.delete()
    }

  @Test
  fun `only solids export at a fixed frame`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val composition =
        compositionOf {
          solid(AuthoredColors.MID_GREEN, 1.seconds)
          frame(OutputFrame.Fixed(FIXED))
        }
      val output = File.createTempFile("filmstrip-solid-only", ".mp4").also { it.delete() }

      val info = fixture.exported(fixture.plannedRun(composition, ExportSpec(frameRate = RATE)), output)

      info.video.shouldNotBeNull().displaySize shouldBe FIXED
      val frames = writtenFrames(output.absolutePath, middleOnly = true)
      assertOnTheGrid(frames, RATE.toLong(), RATE, "one second of solid")
      assertReads(frames, channelsOf(AuthoredColors.MID_GREEN), "the solid", TOLERANCE)
      output.delete()
    }

  // A translucent solid on the primary track is composited over the fill, the same as a translucent still.
  @Test
  fun `a translucent solid shows the fill through it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val composition =
        compositionOf {
          solid(HALF_WHITE, 1.seconds)
          fill(Fill.Solid(BLACK))
          frame(OutputFrame.Fixed(FIXED))
        }
      val output = File.createTempFile("filmstrip-solid-alpha", ".mp4").also { it.delete() }

      fixture.exported(fixture.plannedRun(composition, ExportSpec(frameRate = RATE)), output)

      assertReads(writtenFrames(output.absolutePath, middleOnly = true), HALF_GREY, "half white over black", TOLERANCE)
      output.delete()
    }

  private companion object {
    const val RATE = 30
    const val CLIP_BLUE = 200
    val FIXED = Size(320, 240)
    val CORNER = 2 to 2
    val TOLERANCE = AuthoredColors.TOLERANCE.toInt()
    const val SILENT = 0.001f
    const val AUDIBLE = 0.1f
    val HALF_WHITE = 0x80FFFFFF.toInt()
    val BLACK = 0xFF000000.toInt()
    val HALF_GREY = Triple(128, 128, 128)
  }
}
