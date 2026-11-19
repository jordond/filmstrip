@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.preview

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.toByteArray
import dev.jordond.filmstrip.webcodecs.internal.render.BrowserCompositor
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * One composited preview frame, in the packed form a surface draws and a comparison reads.
 *
 * @property pixels Tightly packed RGBA_8888, row major, `width * height * 4` bytes.
 * @property size The frame's dimensions.
 * @property presentationTime The composition time this frame sits at on the output frame grid,
 *   which is the time an export writes it at rather than the time that was asked for.
 */
@InternalFilmstripApi
public class PreviewFrame internal constructor(
  public val pixels: ByteArray,
  public val size: Size,
  public val presentationTime: Duration,
)

/**
 * One composited preview frame on a kept HDR grade, as the ten-bit codes the encoder takes.
 *
 * The codes are video range BT.2020 luma, Cb and Cr. The encoder's planes hold chroma at half resolution, so a pixel
 * carries the Cb and Cr of the two by two block it sits in, and a read away from an edge between colours sees exactly
 * what the export encodes there.
 *
 * @property codes Luma, Cb and Cr for each pixel in that order, row major, `width * height * 3` entries.
 * @property size The frame's dimensions.
 * @property presentationTime The composition time this frame sits at on the output frame grid.
 * @property transfer The grade's transfer, which the codes are encoded in.
 */
@InternalFilmstripApi
public class PreviewCodes internal constructor(
  public val codes: ShortArray,
  public val size: Size,
  public val presentationTime: Duration,
  public val transfer: HdrTransfer,
)

/**
 * The frame this pass composited for [render], presented at [outputUs] and copied out as packed RGBA.
 */
internal suspend fun BrowserCompositor.readFrame(
  render: BrowserRender,
  outputUs: Double,
): PreviewFrame {
  val shot = present(outputUs, render.stepUs)
  try {
    val options = JsOptions().put("format", "RGBA").build()
    val target = Uint8Array(shot.allocationSize(options))
    shot.copyTo(target, options).await()
    return PreviewFrame(
      pixels = target.toByteArray(),
      size = Size(render.width, render.height),
      presentationTime = outputUs.microseconds,
    )
  } finally {
    shot.close()
  }
}

/**
 * The frame this pass composited for [render] as the ten-bit codes an export encodes, or null when [render] is SDR.
 */
internal fun BrowserCompositor.readCodes(
  render: BrowserRender,
  outputUs: Double,
): PreviewCodes? {
  val transfer = render.hdrTransfer ?: return null
  val codes = tenBitCodes() ?: return null
  return PreviewCodes(codes, Size(render.width, render.height), outputUs.microseconds, transfer)
}
