package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A sliding overlay scrubbed into on the ffmpeg pump, against a real ffmpeg and a real clip.
 *
 * Nothing here skips. The fixtures are a task dependency of `jvmTest` and the jvm lanes carry ffmpeg, so a missing one
 * is a broken build rather than a reason to report green.
 */
class FfmpegPreviewSlideMediaTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")

  private val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
  private val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))

  // A slide is placed by the merge, which reads the clock of the frame it draws on, and that clock restarts where the
  // scrub lands. So the probe sits in the middle of the ramp, where a slide restarted at the scrub is drawn where the
  // slide opens instead. The slide also steps to a new place on every frame, so each frame read after the scrub has to
  // be drawn with its own offset rather than a neighbour's.
  @Test
  fun `a seek into a sliding overlay draws what the export draws`() =
    runTest(timeout = TIMEOUT) {
      val stepping =
        OverlayAnimation { time ->
          val frame = (time.elapsed.inWholeMicroseconds * FIXTURE_RATE / MICROS).toInt()
          OverlayFrame(offset = OverlayOffset(SLIDE_FROM.x * (frame * SLIDE_STEP % SLIDE_STEPS) / SLIDE_STEPS, 0f))
        }
      val sliding = overlaid(watermark(stepping))

      val landed = frames(sliding, PROBE_POSITION, SLIDE_FRAMES)
      val forward = frames(sliding, Duration.ZERO, PROBE_FRAME + SLIDE_FRAMES).drop(PROBE_FRAME)
      landed.indices.forEach { index ->
        withClue("frame $index after the scrub drew a different slide than playback did") {
          forward[index].contentEquals(landed[index]).shouldBeTrue()
        }
      }

      val restarted =
        frames(overlaid(watermark(OverlayAnimation { OverlayFrame(offset = SLIDE_FROM) })), PROBE_POSITION, 1).single()
      withClue("the scrubbed slide was drawn where the slide opens") {
        landed.first().contentEquals(restarted).shouldBeFalse()
      }
    }

  private suspend fun frames(
    composition: EditComposition,
    at: Duration,
    count: Int,
  ): List<ByteArray> {
    val result = engine.openPreview(composition, ExportSpec(), at = at)
    val stream =
      result.shouldBeInstanceOf<PreviewStreamResult.Opened>().stream.shouldBeInstanceOf<FfmpegPreviewStream>()
    return try {
      buildList {
        repeat(count) { index ->
          add(withClue("the pump ran out before frame $index at $at") { stream.next().shouldNotBeNull() })
        }
      }
    } finally {
      stream.close()
    }
  }

  private fun overlaid(overlay: ImageOverlay): EditComposition {
    withClue("the fixture ${landscape.absolutePath} was not downloaded") { landscape.isFile.shouldBeTrue() }
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of(landscape.absolutePath))))),
      effects = listOf(overlay),
    )
  }

  // A white square in the corner the frame starts at, a quarter of the frame wide.
  private fun watermark(animation: OverlayAnimation): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(WHITE_SQUARE),
      corner = Corner.TopStart,
      margin = 0f,
      scale = OVERLAY_SCALE,
      animation = animation,
    )

  private companion object {
    val TIMEOUT = 2.minutes

    // Frame 12 on the fixture's 30fps grid, in the middle of the clip rather than at either end.
    val PROBE_POSITION = 400.milliseconds
    const val PROBE_FRAME = 12
    const val FIXTURE_RATE = 30
    const val MICROS = 1_000_000L

    const val OVERLAY_SCALE = 0.25f

    // The farthest right a stepping slide puts the square, three tenths of the frame, and how it steps: frame k sits at
    // (k * SLIDE_STEP mod SLIDE_STEPS) twenty-firsts of that, so no two neighbouring frames share a place.
    val SLIDE_FROM = OverlayOffset(0.3f, 0f)
    const val SLIDE_STEP = 13
    const val SLIDE_STEPS = 21
    const val SLIDE_FRAMES = 8

    val WHITE_SQUARE: ByteArray = pngOf(Color.WHITE.rgb, OVERLAY_SIZE)
  }
}
