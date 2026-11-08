package dev.jordond.filmstrip.media3

import android.graphics.Color
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels.ENCODED_TOLERANCE
import dev.jordond.filmstrip.test.OverlayPixels.pngOf
import dev.jordond.filmstrip.test.OverlayPixels.sourceOver
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Partly covered overlay texels on an SDR export, on real media3.
 *
 * media3's overlay shader reads each texel as straight alpha and multiplies by that alpha itself, so a
 * bitmap handed over premultiplied lands its soft edges darker than a source over draw puts them. Each
 * test paints the alpha into the overlay image itself, since the overlay's opacity never goes through the
 * bitmap and so cannot show the difference.
 *
 * The expected level is a source over draw between two readings off the same frame, one export with no
 * overlay and one with the same overlay opaque, so the encoder's own handling of both ends cancels out.
 * Skipped when the fixtures are absent, as in [AndroidExportMediaTest].
 */
class AndroidOverlaySdrMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aHalfAlphaTexelBlendsStraightOverTheFill() = runTest(timeout = TIMEOUT) { assertStraight(HALF) }

  @Test
  fun aQuarterAlphaTexelBlendsStraightOverTheFill() = runTest(timeout = TIMEOUT) { assertStraight(QUARTER) }

  // The badge sits in the bar a square crop leaves, over a mid grey fill, so what is under it is a known
  // color rather than a picture.
  private suspend fun assertStraight(alpha: Float) {
    val source = probe.fixture()
    val texelAlpha = (alpha * MAX_CODE).roundToInt()
    val plain = probe.frame(probe.export(letterboxed(source, emptyList())))
    val opaque = probe.frame(probe.export(letterboxed(source, listOf(barMark(WHITE)))))
    val soft = probe.frame(probe.export(letterboxed(source, listOf(barMark(WHITE.withAlpha(texelAlpha))))))

    val frame = plain.frameSize()
    val core = barMark(WHITE).placedOn(frame, BADGE).core(frame)
    val fx = (core.left + core.right) / 2f
    val fy = (core.top + core.bottom) / 2f
    val under = plain.averageAt(fx, fy).toList()
    val over = opaque.averageAt(fx, fy).toList()
    val read = soft.averageAt(fx, fy).toList()

    withClue("the overlay's patch is not in the bar, it reads $under") {
      under.all { abs(it - Color.red(FILL_ARGB)) < AuthoredColors.TOLERANCE } shouldBe true
    }
    val expected = under.indices.map { sourceOver(under[it], over[it], texelAlpha / MAX_CODE) }
    val off = expected.indices.maxOf { abs(expected[it] - read[it]) }
    withClue("a texel at alpha $texelAlpha read $read, straight source over puts it at $expected, $off codes off") {
      (off <= ENCODED_TOLERANCE) shouldBe true
    }
  }

  private fun barMark(argb: Int) =
    ImageOverlay(ImageSource.Bytes(pngOf(BADGE, argb)), Corner.TopStart, BAR_MARGIN, BAR_SCALE)

  private fun letterboxed(
    source: MediaSource,
    effects: List<EffectSpec>,
  ) = EditComposition(
    listOf(Track(listOf(Clip(source)))),
    listOf(Crop(AspectRatio.Square, fit = Fit.Contain)) + effects,
    fill = Fill.Solid(FILL_ARGB),
  )

  private fun Int.withAlpha(alpha: Int): Int = (this and RGB_MASK) or (alpha shl ALPHA_SHIFT)

  private companion object {
    const val FILL_ARGB = AuthoredColors.MID_GREY
    const val WHITE = 0xFFFFFFFF.toInt()

    const val HALF = 0.5f
    const val QUARTER = 0.25f
    const val MAX_CODE = 255f

    const val RGB_MASK = 0x00FFFFFF
    const val ALPHA_SHIFT = 24

    // Small enough that, inset from the top corner, it fits inside the bar a square crop leaves.
    const val BAR_MARGIN = 0.02f
    const val BAR_SCALE = 0.12f
  }
}
