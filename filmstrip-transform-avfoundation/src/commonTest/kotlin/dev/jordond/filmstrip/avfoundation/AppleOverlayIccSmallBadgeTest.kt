package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AuthoredColors
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

/**
 * Color described overlay pictures drawn onto an SDR export as small badges and read back out of the written file.
 *
 * In the frame Core Image hands the encoder a badge is held exact to its sRGB codes, all but the HEIC and JPEG ImageIO
 * wrote, and once exported to within a code and a share of the chroma step from the fill around it.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayIccSmallBadgeTest : AppleOverlayIccCase() {
  // Core Image draws a small badge's centre exact. VideoToolbox's lossy encode then rings into it from its edges, so
  // its chroma there lands off by a share of the chroma step between the badge and the fill around it.
  @Test
  fun `a small badge is drawn at the shared srgb codes and exported within a share of its step from the fill`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT)
      val context = floatContext()
      val fill = AuthoredColors.channelsOf(AuthoredColors.MID_GREY).map { it.toFloat() }
      val misses = mutableListOf<String>()

      PICTURES.chunked(CORNERS.size).forEachIndexed { index, pictures ->
        val badges = pictures.zip(CORNERS) { picture, corner -> picture.badgeAt(corner, SMALL_SCALE) }
        val output = NSTemporaryDirectory() + "filmstrip-apple-icc-small-$index.mp4"
        val composition =
          compositionOf {
            clip(MediaSource.of(portrait)) { trim(Duration.ZERO, CLIP) }
            fill(Fill.Solid(AuthoredColors.MID_GREY))
            effects {
              crop(AspectRatio.Landscape, Fit.Contain)
              badges.forEach { add(it) }
            }
          }
        val statuses =
          withContext(Dispatchers.Default) { filmstrip.export(composition, SMALL_BADGE_SDR, MediaSink.of(output)) }
        val finished = statuses.toList().last()
        if (finished is ExportStatus.Failure) error("the small badge export failed: ${finished.error.message}")
        val size =
          finished
            .shouldBeInstanceOf<ExportStatus.Success>()
            .info.video
            .shouldNotBeNull()
            .displaySize
        val exported = frameOf(output, CLIP / 2)
        NSFileManager.defaultManager.removeItemAtPath(output, error = null)
        val drawnOverlays = badges.zip(pictures.map { it.degradedBy })
        val drawn =
          drawnSdrFrame(size, AuthoredColors.MID_GREY, drawnOverlays, TimeRange.of(Duration.ZERO, CLIP), CLIP / 2)
        pictures.forEach { it.removeFile() }

        pictures.zip(badges).mapNotNullTo(misses) { (picture, badge) ->
          val rect = badge.placedOn(size, picture.size).rectOn(size)
          val x = (rect.left + rect.right) / 2f
          val y = (rect.top + rect.bottom) / 2f
          AppleStillSamples.missOrUnmeasured {
            val colors = picture.srgbCodes()
            // ImageIO's HEIC and JPEG decode a little off the texel they were written with, so only their export is
            // held.
            val drawnMiss =
              if (picture !in ENCODED) {
                val read = drawn.sdrCodesAt(context, size, x, y)
                "the small ${picture.name} badge was drawn at $read, where its color is one of $colors".takeIf {
                  colors.none { color -> read.indices.all { abs(read[it] - color[it]) <= DRAWN_TOLERANCE } }
                }
              } else {
                null
              }
            listOfNotNull(drawnMiss, exportMiss(picture, colors, exported.average(x, y), fill))
              .joinToString("\n")
              .ifEmpty { null }
          }
        }
      }

      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  // Why the badge exported at sampled sits further from every one of colors than the encode's ringing reaches, or null
  // when it sits within that of one. How far its blue and red differences landed is printed either way.
  private fun exportMiss(
    picture: Picture,
    colors: List<List<Float>>,
    sampled: Triple<Int, Int, Int>,
    fill: List<Float>,
  ): String? {
    val read = sampled.inBufferCodes().map { it * FULL_SCALE }
    val landed = chroma(read)
    val ringing =
      colors.map { color ->
        val step = chroma(color).zip(chroma(fill)) { inBadge, around -> abs(inBadge - around) }
        chroma(color).zip(landed) { inBadge, exportedAt -> abs(exportedAt - inBadge) }.zip(step)
      }
    val measured = ringing.map { pairs -> pairs.map { (off, step) -> "$off of $step" } }
    println("the small ${picture.name} badge's blue and red differences exported off by $measured from the fill")
    if (ringing.any { pairs -> pairs.all { (off, step) -> off <= ENCODE_FLOOR + RINGING_SHARE * step } }) return null
    return "the small ${picture.name} badge exported at $read, its blue and red differences off one of $colors by " +
      "${ringing.map { pairs -> pairs.map { it.first } }}, past $ENCODE_FLOOR and $RINGING_SHARE of their steps from " +
      "the fill ${ringing.map { pairs -> pairs.map { it.second } }}"
  }

  private companion object {
    // The SDR export at a bitrate of its own. The output settings assistant's for a 180 pixel frame is low enough that
    // how far the encode rings into a badge's centre moves with the machine it runs on.
    val SMALL_BADGE_SDR = ExportSpec(heightPx = SDR.heightPx, bitrate = Bitrate.mbps(8))
  }
}
