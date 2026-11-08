package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREY
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Authored colors in an SDR export, read back off the written file.
 *
 * A solid fill, a text plate and an overlay image's pixels land in the buffer as their own codes, with no color
 * management on the way. The probe reads the frame in device RGB, so each reading is carried back into the buffer's
 * BT.709 codes and every channel is checked against the authored code within [AuthoredColors.TOLERANCE], which a
 * color managed write in either direction falls outside of.
 *
 * Each export draws its overlays in one color over a fill in the other, so an overlay that never drew reads as the
 * wrong color rather than passing on the fill's.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleAuthoredColorHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `lands a mid grey fill and mid green overlays as their own codes`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT)
      assertLandsAsAuthored(portrait, fill = MID_GREY, overlay = MID_GREEN, "grey-fill")
    }

  @Test
  fun `lands a mid green fill and mid grey overlays as their own codes`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT)
      assertLandsAsAuthored(portrait, fill = MID_GREEN, overlay = MID_GREY, "green-fill")
    }

  private suspend fun assertLandsAsAuthored(
    portrait: String,
    fill: Int,
    overlay: Int,
    name: String,
  ) {
    val pixels = AuthoredColors.channelsOf(overlay).let { Triple(it[0], it[1], it[2]) }
    val badge = ImageOverlay(ImageSource.ofBytes(solidPng(RASTER.width, RASTER.height, pixels)), Corner.TopStart)
    val plate = TextStyle(fontSize = FONT_SIZE, color = overlay, backgroundColor = overlay)
    val caption = TextOverlay(CAPTION, plate)
    val composition =
      compositionOf {
        clip(MediaSource.of(portrait)) { trim(Duration.ZERO, CLIP) }
        fill(Fill.Solid(fill))
        effects {
          crop(AspectRatio.Landscape, Fit.Contain)
          add(badge)
          add(caption)
        }
      }

    val output = NSTemporaryDirectory() + "filmstrip-apple-authored-$name.mp4"
    val size =
      exported(composition, output)
        .info.video
        .shouldNotBeNull()
        .displaySize
    val source = frameOf(portrait)
    val bar = (1f - source.width.toFloat() / source.height * size.height / size.width) / 2f
    val rect = badge.placedOn(size, RASTER).rectOn(size)
    withClue("the badge reaches ${rect.right}, past the bar at $bar") { (rect.right < bar).shouldBeTrue() }

    val drawn = frameOf(output, CLIP / 2)
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)

    // The caption's glyphs are drawn in its plate's color, so the whole block reads as the plate. Its bottom edge
    // sits on the frame's, and it stands at least a cap height tall.
    assertAuthored(drawn.average(1f - bar / 2f, 0.5f), fill, "the fill in the far bar")
    assertAuthored(drawn.average((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f), overlay, "the badge")
    assertAuthored(drawn.average(0.5f, 1f - FONT_SIZE / 2f), overlay, "the caption's plate")
  }

  private fun assertAuthored(
    drawn: Triple<Int, Int, Int>,
    authored: Int,
    label: String,
  ) {
    val codes = drawn.inBufferCodes().map { it * FULL_SCALE }
    val expected = AuthoredColors.channelsOf(authored)

    expected.indices.forEach { channel ->
      withClue("$label read $codes in the buffer's codes, where $expected was authored") {
        (abs(codes[channel] - expected[channel]) <= AuthoredColors.TOLERANCE).shouldBeTrue()
      }
    }
  }

  private suspend fun exported(
    composition: EditComposition,
    output: String,
  ): ExportStatus.Success =
    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, SPEC, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }

  private companion object {
    const val PORTRAIT = "apple_export_portrait.mp4"
    val TIMEOUT = 2.seconds * 60
    val SPEC = ExportSpec(heightPx = 180, bitrate = COLOR_READ_BITRATE)
    val CLIP = 1.seconds
    val RASTER = Size(4, 2)
    const val CAPTION = "Hi"
    const val FONT_SIZE = 0.25f
    const val FULL_SCALE = 255f
  }
}
