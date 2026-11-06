package dev.jordond.filmstrip.effects.filters.crt

import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.geometry.Size
import java.util.stream.IntStream
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The frames that hold everything about a [Crt] that stays still, worked out once from [CrtSignal] at one frame size.
 *
 * Each one is a sixteen-bit image in a sidecar, so ffmpeg reads it once and holds it for the whole run. Four hold the
 * columns and rows either side of where each pixel reads from, clamped to the frame, and two hold how far the read sits
 * between them. [multiplier] holds what each channel is multiplied by, as a share of the gain, and [coverage] holds
 * [CrtSignal.coverageAt].
 *
 * @property leftColumn The column at or before each pixel's read.
 * @property rightColumn The column after each pixel's read.
 * @property topRow The row at or before each pixel's read.
 * @property bottomRow The row after each pixel's read.
 * @property across How far each read sits from [leftColumn] to [rightColumn].
 * @property down How far each read sits from [topRow] to [bottomRow].
 * @property multiplier The scanlines, mask and vignette of each pixel, red, green and blue.
 * @property coverage The share of each pixel that holds picture.
 */
internal class CrtFrames private constructor(
  val leftColumn: Sidecar,
  val rightColumn: Sidecar,
  val topRow: Sidecar,
  val bottomRow: Sidecar,
  val across: Sidecar,
  val down: Sidecar,
  val multiplier: Sidecar,
  val coverage: Sidecar,
) {
  /**
   * Every file this set is made of.
   */
  val sidecars: List<Sidecar>
    get() = listOf(leftColumn, rightColumn, topRow, bottomRow, across, down, multiplier, coverage)

  companion object {
    /**
     * Draws the frames for [params] at [frame].
     */
    fun of(
      params: CrtParams,
      frame: Size,
    ): CrtFrames {
      val width = frame.width
      val height = frame.height
      val gray = List(GRAY_FRAMES) { SixteenBitImage(width, height, channels = 1) }
      val (left, right, top, bottom) = gray
      val across = gray[ACROSS]
      val down = gray[DOWN]
      val coverage = gray[COVERAGE]
      val multiplier = SixteenBitImage(width, height, channels = RGB)

      IntStream.range(0, height).parallel().forEach { y ->
        val v = (y + CENTRE) / height
        for (x in 0 until width) {
          val u = (x + CENTRE) / width
          val source = CrtSignal.barrelMap(u, v, width, height, params.barrelK)
          val column = source[0] * width - CENTRE
          val row = source[1] * height - CENTRE
          val firstColumn = floor(column)
          val firstRow = floor(row)
          left.put(x, y, 0, firstColumn.toInt().coerceIn(0, width - 1))
          right.put(x, y, 0, (firstColumn.toInt() + 1).coerceIn(0, width - 1))
          top.put(x, y, 0, firstRow.toInt().coerceIn(0, height - 1))
          bottom.put(x, y, 0, (firstRow.toInt() + 1).coerceIn(0, height - 1))
          across.putShare(x, y, 0, column - firstColumn)
          down.putShare(x, y, 0, row - firstRow)
          coverage.putShare(x, y, 0, CrtSignal.coverageAt(u, v, width, height, params.barrelK))
          for (channel in 0 until RGB) {
            multiplier.putShare(
              x,
              y,
              channel,
              CrtSignal.multiplierAt(u, v, width, height, params, channel) / params.gain,
            )
          }
        }
      }

      return CrtFrames(
        leftColumn = left.sidecar(),
        rightColumn = right.sidecar(),
        topRow = top.sidecar(),
        bottomRow = bottom.sidecar(),
        across = across.sidecar(),
        down = down.sidecar(),
        multiplier = multiplier.sidecar(),
        coverage = coverage.sidecar(),
      )
    }
  }
}

// A netpbm image at full sixteen-bit scale, whose samples are stored big endian as the format has them.
private class SixteenBitImage(
  private val width: Int,
  height: Int,
  private val channels: Int,
) {
  private val magic = if (channels == 1) "P5" else "P6"
  private val header = "$magic\n$width $height\n$FULL_CODE\n".encodeToByteArray()
  private val bytes = header.copyOf(header.size + width * height * channels * BYTES_PER_SAMPLE)

  fun put(
    x: Int,
    y: Int,
    channel: Int,
    code: Int,
  ) {
    val at = header.size + ((y * width + x) * channels + channel) * BYTES_PER_SAMPLE
    bytes[at] = (code shr BITS_PER_BYTE).toByte()
    bytes[at + 1] = code.toByte()
  }

  fun putShare(
    x: Int,
    y: Int,
    channel: Int,
    share: Double,
  ) = put(x, y, channel, (share * FULL_CODE).roundToInt().coerceIn(0, FULL_CODE))

  fun sidecar(): Sidecar = Sidecar(bytes, if (channels == 1) GRAY_EXTENSION else RGB_EXTENSION)
}

/**
 * The extension of a sidecar holding one sample a pixel.
 */
internal const val GRAY_EXTENSION = "pgm"

/**
 * The extension of a sidecar holding red, green and blue.
 */
internal const val RGB_EXTENSION = "ppm"

/**
 * The code a [CrtFrames] share of one is stored as, and what the frame's `blend` nodes divide by.
 */
internal const val FULL_CODE = 65535

private const val GRAY_FRAMES = 7
private const val ACROSS = 4
private const val DOWN = 5
private const val COVERAGE = 6
private const val RGB = 3
private const val CENTRE = 0.5
private const val BYTES_PER_SAMPLE = 2
private const val BITS_PER_BYTE = 8
