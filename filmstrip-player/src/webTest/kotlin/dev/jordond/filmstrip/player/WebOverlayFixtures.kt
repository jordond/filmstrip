package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.TestFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pixel size of [BADGE], which is what `placedOn` reads its aspect from.
 *
 * On the fixture's frame the badge is drawn at this size, from an even row and column over an even
 * number of each. H.264 carries color at half resolution in two by two blocks, so an edge splitting
 * a block would smear the badge's color into the row past it in the export and not in the preview.
 */
internal val BADGE_SIZE: Size = Size(26, 12)

/**
 * The color [BADGE] is painted, one of the authored colors every backend's SDR pins read back.
 */
internal const val BADGE_COLOR: Int = AuthoredColors.MID_GREEN

/**
 * An opaque image overlay source painted [BADGE_COLOR] all over, for the web contract suites.
 */
internal val BADGE: ImageSource by lazy { ImageSource.ofBytes(OverlayPixels.pngOf(BADGE_SIZE, BADGE_COLOR)) }

/**
 * The red, green and blue codes of one reading off a frame.
 */
internal data class Rgb(
  val red: Int,
  val green: Int,
  val blue: Int,
) {
  /**
   * Where a source over draw of [overlay] at [opacity] leaves this color, worked on the codes
   * themselves.
   */
  fun towards(
    overlay: Rgb,
    opacity: Float,
  ): Rgb =
    Rgb(
      OverlayPixels.sourceOver(red, overlay.red, opacity),
      OverlayPixels.sourceOver(green, overlay.green, opacity),
      OverlayPixels.sourceOver(blue, overlay.blue, opacity),
    )

  companion object {
    /**
     * The color channels of an ARGB int.
     */
    fun of(argb: Int): Rgb {
      val (red, green, blue) = AuthoredColors.channelsOf(argb)
      return Rgb(red, green, blue)
    }
  }
}

/**
 * The mean color of the three by three block around the middle of [rect], so one pixel's rounding
 * does not decide a reading.
 */
internal fun TestFrame.around(rect: NormalizedRect): Rgb {
  val column = ((rect.left + rect.right) / 2 * size.width).toInt()
  val row = ((rect.top + rect.bottom) / 2 * size.height).toInt()
  val samples =
    (-1..1).flatMap { dy ->
      (-1..1).map { dx ->
        val offset = ((row + dy) * size.width + column + dx) * RGBA_CHANNELS
        Rgb(pixels[offset].unsignedCode, pixels[offset + 1].unsignedCode, pixels[offset + 2].unsignedCode)
      }
    }
  return Rgb(
    samples.map { it.red }.average().roundToInt(),
    samples.map { it.green }.average().roundToInt(),
    samples.map { it.blue }.average().roundToInt(),
  )
}

/**
 * Fails unless every channel of [drawn] sits within [tolerance] codes of [expected].
 */
internal fun assertReads(
  expected: Rgb,
  drawn: Rgb,
  what: String,
  tolerance: Float = AuthoredColors.TOLERANCE,
) {
  val off = maxOf(abs(drawn.red - expected.red), abs(drawn.green - expected.green), abs(drawn.blue - expected.blue))
  withClue("$what read $drawn, where $expected was expected within $tolerance") { (off <= tolerance).shouldBeTrue() }
}

private val Byte.unsignedCode: Int get() = toInt() and BYTE

private const val RGBA_CHANNELS = 4
private const val BYTE = 0xFF
