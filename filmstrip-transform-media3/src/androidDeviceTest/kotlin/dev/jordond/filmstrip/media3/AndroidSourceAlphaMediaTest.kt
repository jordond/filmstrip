package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.writeAtomically
import dev.jordond.filmstrip.transform.internal.geometry.blendOverFill
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A picture with alpha exported over a solid and a blurred fill, read against [blendOverFill].
 *
 * The picture fills the frame, so no bar shows and the fill is reached only through the picture's own alpha. Every
 * expectation reads the source off an export of the same picture at full alpha, so the codes carry the encoder's own
 * rounding, and the alpha is a middle value so a pass that applies it twice or not at all reads apart from the blend.
 */
class AndroidSourceAlphaMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aHalfTransparentPictureOverASolidFillReadsTheSharedBlend() =
    runTest(timeout = TIMEOUT) {
      val fill = Fill.Solid(SLATE)
      val opaque = probe.written(composition(picture(OPAQUE_ALPHA), fill)).rgbAt(LEFT_AT, MID_ROW)
      val clear = probe.written(composition(picture(0), fill)).rgbAt(LEFT_AT, MID_ROW)
      val half = probe.written(composition(picture(HALF_ALPHA), fill)).rgbAt(LEFT_AT, MID_ROW)

      assertBlend(half, opaque, clear)
    }

  @Test
  fun aHalfTransparentPictureOverABlurredFillReadsTheSharedBlendOfItsOwnCover() =
    runTest(timeout = TIMEOUT) {
      val fill = Fill.Blurred(radius = RADIUS, dim = 0f)
      val frame = probe.plan(composition(picture(OPAQUE_ALPHA), fill)).output.size
      val opaque = probe.written(composition(picture(OPAQUE_ALPHA), fill))
      val half = probe.written(composition(picture(HALF_ALPHA), fill))

      val left = opaque.rgbAt(LEFT_AT, MID_ROW)
      val right = opaque.rgbAt(RIGHT_AT, MID_ROW)
      val sigma = fill.blurPlan(frame).let { it.sigmaPx / it.width }
      for (at in listOf(LEFT_AT, NEAR_STEP_AT)) {
        val cover =
          listOf(left.first to right.first, left.second to right.second, left.third to right.third).map { (l, r) ->
            val plane =
              DoubleArray(frame.width * frame.height) {
                if (it % frame.width < frame.width / 2) l.toDouble() else r.toDouble()
              }
            val blurred = blurPlane(plane, DoubleArray(plane.size) { 1.0 }, frame.width, frame.height, sigma)
            blurred[(MID_ROW * frame.height).toInt() * frame.width + (at * frame.width).toInt()].toInt()
          }
        val source = opaque.rgbAt(at, MID_ROW)
        withClue("the cover has to differ from the source or this cannot tell a blend from no blend") {
          abs(cover[0] - source.first) shouldBeGreaterThan MIN_COVER_GAP
        }
        assertBlend(half.rgbAt(at, MID_ROW), source, Triple(cover[0], cover[1], cover[2]))
      }
    }

  @Test
  fun aPictureWithAClearHalfIsCoveredByABlurWeightedByItsAlpha() =
    runTest(timeout = TIMEOUT) {
      val fill = Fill.Blurred(radius = CLEAR_RADIUS, dim = 0f)
      val frame = probe.plan(composition(picture(OPAQUE_ALPHA), fill)).output.size
      val source = probe.written(composition(picture(OPAQUE_ALPHA), fill)).rgbAt(LEFT_AT, MID_ROW)
      val drawn = probe.written(composition(clearHalfPicture(), fill))
      val sigma = fill.blurPlan(frame).let { it.sigmaPx / it.width }
      val alpha = DoubleArray(frame.width * frame.height) { if (it % frame.width < frame.width / 2) 1.0 else 0.0 }

      for (at in listOf(CLEAR_AT, NEAR_EDGE_AT)) {
        val cover =
          listOf(source.first, source.second, source.third).map { level ->
            val plane = DoubleArray(alpha.size) { if (alpha[it] > 0.0) level.toDouble() else 0.0 }
            blurPlane(plane, alpha, frame.width, frame.height, sigma)[
              (MID_ROW * frame.height).toInt() * frame.width + (at * frame.width).toInt(),
            ].roundToInt()
          }
        assertWithin(drawn.rgbAt(at, MID_ROW), Triple(cover[0], cover[1], cover[2]), "at $at")
      }
    }

  private fun assertWithin(
    read: Triple<Int, Int, Int>,
    expected: Triple<Int, Int, Int>,
    context: String,
  ) {
    val off =
      maxOf(
        abs(read.first - expected.first),
        abs(read.second - expected.second),
        abs(read.third - expected.third),
      )
    withClue("$context, read $read, expected $expected, off by $off") { off shouldBeLessThanOrEqual TOLERANCE }
  }

  private fun assertBlend(
    read: Triple<Int, Int, Int>,
    source: Triple<Int, Int, Int>,
    fill: Triple<Int, Int, Int>,
  ) {
    val expected =
      Triple(
        blendOverFill(source.first, HALF_ALPHA, fill.first),
        blendOverFill(source.second, HALF_ALPHA, fill.second),
        blendOverFill(source.third, HALF_ALPHA, fill.third),
      )
    assertWithin(read, expected, "source $source, fill $fill")
  }

  private fun composition(
    source: MediaSource,
    fill: Fill,
  ): Composition = Composition(listOf(Track(listOf(Clip(source)))), fill = fill)

  // Warm on the left and cool on the right, the whole picture at [alpha].
  private fun picture(alpha: Int): MediaSource {
    val file = File(probe.context.cacheDir, "source-alpha-$alpha.png")
    if (!file.exists()) {
      val pixels =
        IntArray(SOURCE.width * SOURCE.height) {
          val rgb = if (it % SOURCE.width < SOURCE.width / 2) WARM else COOL
          (alpha shl ALPHA_SHIFT) or rgb
        }
      val bitmap = Bitmap.createBitmap(pixels, SOURCE.width, SOURCE.height, Bitmap.Config.ARGB_8888)
      file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }
    return MediaSource.Image(ImageSource.of(file.path), LENGTH)
  }

  // Opaque warm on the left and clear black on the right.
  private fun clearHalfPicture(): MediaSource {
    val file = File(probe.context.cacheDir, "source-alpha-clear-half.png")
    if (!file.exists()) {
      val pixels =
        IntArray(SOURCE.width * SOURCE.height) {
          if (it % SOURCE.width < SOURCE.width / 2) (OPAQUE_ALPHA shl ALPHA_SHIFT) or WARM else 0
        }
      val bitmap = Bitmap.createBitmap(pixels, SOURCE.width, SOURCE.height, Bitmap.Config.ARGB_8888)
      file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }
    return MediaSource.Image(ImageSource.of(file.path), LENGTH)
  }

  private companion object {
    val SOURCE = Size(640, 360)
    val LENGTH = 500.milliseconds

    const val WARM = 0xE61E1E
    const val COOL = 0x1E1EE6
    const val SLATE = 0xFF2F4F6F.toInt()
    const val OPAQUE_ALPHA = 255
    const val HALF_ALPHA = 128
    const val ALPHA_SHIFT = 24
    const val PNG_QUALITY = 100

    // A sigma wide enough that the cover under the left half is part way to the right.
    const val RADIUS = 0.5f
    const val LEFT_AT = 0.25f
    const val RIGHT_AT = 0.75f
    const val NEAR_STEP_AT = 0.4f
    const val CLEAR_AT = 0.75f
    const val NEAR_EDGE_AT = 0.6f
    const val CLEAR_RADIUS = 0.25f
    const val MID_ROW = 0.5f
    const val MIN_COVER_GAP = 12

    // What an encoder moves a read by.
    const val TOLERANCE = 8
  }
}
