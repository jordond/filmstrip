package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AuthoredColours
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Colour described overlay pictures drawn onto an SDR export as small badges and read back out of the written file.
 *
 * In the frame Core Image hands the encoder a badge is held exact to its sRGB codes, all but the HEIC and JPEG ImageIO
 * wrote, and once exported to within a code and a share of the chroma step from the fill around it.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
class AppleOverlayIccSmallBadgeTest : AppleOverlayIccCase() {
  // Core Image draws a small badge's centre exact. VideoToolbox's lossy encode then rings into it from its edges, so
  // its chroma there lands off by a share of the chroma step between the badge and the fill around it.
  @Test
  fun `a small badge is drawn at the shared srgb codes and exported within a share of its step from the fill`() =
    runTest(timeout = TIMEOUT) {
      val portrait =
        fixture(PORTRAIT) ?: run {
          println("skipping the small badge case, $FIXTURES is unset")
          return@runTest
        }
      val context = floatContextOrNull()
      if (context == null) println("SKIPPED the small badge's drawn frame: no Core Image context here")
      val fill = AuthoredColours.channelsOf(AuthoredColours.MID_GREY).map { it.toFloat() }
      val misses = mutableListOf<String>()

      PICTURES.chunked(CORNERS.size).forEachIndexed { index, pictures ->
        val badges = pictures.zip(CORNERS) { picture, corner -> picture.badgeAt(corner, SMALL_SCALE) }
        val output = NSTemporaryDirectory() + "filmstrip-apple-icc-small-$index.mp4"
        val composition =
          compositionOf {
            clip(MediaSource.of(portrait)) { trim(Duration.ZERO, CLIP) }
            fill(Fill.Solid(AuthoredColours.MID_GREY))
            effects {
              crop(AspectRatio.Landscape, Fit.Contain)
              badges.forEach { add(it) }
            }
          }
        val statuses = withContext(Dispatchers.Default) { filmstrip.export(composition, SDR, MediaSink.of(output)) }
        val finished = statuses.toList().last()
        if (finished is ExportStatus.Failure) error("the small badge export failed: ${finished.error.message}")
        val size = assertNotNull(assertIs<ExportStatus.Success>(finished).info.video).displaySize
        val exported = frameOf(output, CLIP / 2)
        NSFileManager.defaultManager.removeItemAtPath(output, error = null)
        val drawnOverlays = badges.zip(pictures.map { it.degradedBy })
        val drawn =
          drawnSdrFrame(size, AuthoredColours.MID_GREY, drawnOverlays, TimeRange.of(Duration.ZERO, CLIP), CLIP / 2)
        pictures.forEach { it.removeFile() }

        pictures.zip(badges).mapNotNullTo(misses) { (picture, badge) ->
          val rect = badge.placedOn(size, picture.size).rectOn(size)
          val x = (rect.left + rect.right) / 2f
          val y = (rect.top + rect.bottom) / 2f
          AppleStillSamples.missOrUnmeasured {
            val colours = picture.srgbCodes()
            // ImageIO's HEIC and JPEG decode a little off the texel they were written with, so only their export is
            // held.
            val drawnMiss =
              if (context != null && picture !in ENCODED) {
                val read = drawn.sdrCodesAt(context, size, x, y)
                "the small ${picture.name} badge was drawn at $read, where its colour is one of $colours".takeIf {
                  colours.none { colour -> read.indices.all { abs(read[it] - colour[it]) <= DRAWN_TOLERANCE } }
                }
              } else {
                null
              }
            listOfNotNull(drawnMiss, exportMiss(picture, colours, exported.average(x, y), fill))
              .joinToString("\n")
              .ifEmpty { null }
          }
        }
      }

      assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

  // Why the badge exported at sampled sits further from every one of colours than the encode's ringing reaches, or null
  // when it sits within that of one. How far its blue and red differences landed is printed either way.
  private fun exportMiss(
    picture: Picture,
    colours: List<List<Float>>,
    sampled: Triple<Int, Int, Int>,
    fill: List<Float>,
  ): String? {
    val read = sampled.inBufferCodes().map { it * FULL_SCALE }
    val landed = chroma(read)
    val ringing =
      colours.map { colour ->
        val step = chroma(colour).zip(chroma(fill)) { inBadge, around -> abs(inBadge - around) }
        chroma(colour).zip(landed) { inBadge, exportedAt -> abs(exportedAt - inBadge) }.zip(step)
      }
    val measured = ringing.map { pairs -> pairs.map { (off, step) -> "$off of $step" } }
    println("the small ${picture.name} badge's blue and red differences exported off by $measured from the fill")
    if (ringing.any { pairs -> pairs.all { (off, step) -> off <= ENCODE_FLOOR + RINGING_SHARE * step } }) return null
    return "the small ${picture.name} badge exported at $read, its blue and red differences off one of $colours by " +
      "${ringing.map { pairs -> pairs.map { it.first } }}, past $ENCODE_FLOOR and $RINGING_SHARE of their steps from " +
      "the fill ${ringing.map { pairs -> pairs.map { it.second } }}"
  }
}
