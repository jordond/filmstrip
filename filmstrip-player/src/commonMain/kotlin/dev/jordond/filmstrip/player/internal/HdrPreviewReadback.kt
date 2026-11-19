package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.player.PlaybackError
import kotlin.time.Duration

/**
 * Reads the frame a preview composites on a kept HDR grade as the ten-bit codes the export hands its encoder.
 *
 * The read happens before any display conversion, so it holds what the composition decided and nothing a surface
 * did to show it. An engine on an SDR grade, or one whose platform cannot hand back an HDR frame, answers with a
 * failure that names why.
 */
internal fun interface HdrPreviewReadback {
  /**
   * Renders the frame at [position] through the current effect chain and returns its codes.
   *
   * Leaves transport state alone, the same as [dev.jordond.filmstrip.player.PreviewFrameReadback.requestFrame].
   */
  suspend fun hdrFrameAt(position: Duration): HdrPreviewResult
}

/**
 * The outcome of one [HdrPreviewReadback.hdrFrameAt].
 */
internal sealed interface HdrPreviewResult {
  /**
   * The frame was rendered.
   *
   * @property frame The rendered frame.
   */
  class Success(
    val frame: HdrPreviewFrame,
  ) : HdrPreviewResult

  /**
   * No frame was rendered.
   *
   * @property error Why it failed.
   */
  class Failure(
    val error: PlaybackError,
  ) : HdrPreviewResult
}

/**
 * One preview frame on a kept HDR grade, as ten-bit video range BT.2020 luma, Cb and Cr codes at full chroma
 * resolution.
 *
 * Each backend brings chroma up to every pixel its own way. Some repeat the Cb and Cr of a pixel's two by two block,
 * ffmpeg's scaler filters between blocks and media3 never subsamples at all. Inside a flat area every backend reads
 * the codes the export encodes, so a read is meant for the middle of one, away from an edge between colours.
 *
 * @property codes Luma, Cb and Cr for each pixel in that order, row major, `size.width * size.height * 3` entries.
 * @property size The frame's dimensions, in pixels.
 * @property presentationTime The composition time actually rendered.
 * @property transfer The grade's transfer, which the codes are encoded in.
 */
internal class HdrPreviewFrame(
  val codes: ShortArray,
  val size: Size,
  val presentationTime: Duration,
  val transfer: HdrTransfer,
) {
  init {
    require(codes.size == size.width * size.height * CHANNELS) {
      "A ${size.width}x${size.height} frame holds ${size.width * size.height * CHANNELS} codes, not ${codes.size}."
    }
  }

  /**
   * The luma, Cb and Cr codes of the pixel at [x], [y], in that order.
   */
  fun codesAt(
    x: Int,
    y: Int,
  ): IntArray {
    val at = (y * size.width + x) * CHANNELS
    return IntArray(CHANNELS) { codes[at + it].toInt() }
  }

  companion object {
    /**
     * A frame from a [signal] of red, green and blue in the grade's transfer, each pixel encoded through
     * [tenBitCodesFromSignal].
     *
     * @param signal Red, green and blue for each pixel in that order, row major, each from zero to one.
     */
    fun fromSignal(
      signal: FloatArray,
      size: Size,
      presentationTime: Duration,
      transfer: HdrTransfer,
    ): HdrPreviewFrame {
      val pixel = FloatArray(CHANNELS)
      val codes = ShortArray(signal.size)
      for (at in signal.indices step CHANNELS) {
        signal.copyInto(pixel, 0, at, at + CHANNELS)
        tenBitCodesFromSignal(pixel).forEachIndexed { channel, code -> codes[at + channel] = code.toShort() }
      }
      return HdrPreviewFrame(codes, size, presentationTime, transfer)
    }

    private const val CHANNELS = 3
  }
}
