package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.pictureCodesFor
import dev.jordond.filmstrip.color.ycbcrCodes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.HeifCanvasFill
import dev.jordond.filmstrip.picture.heif.HeifGrid
import dev.jordond.filmstrip.picture.heif.HeifGridRead
import dev.jordond.filmstrip.picture.heif.HeifOverlay
import dev.jordond.filmstrip.picture.heif.HeifOverlayRead
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import dev.jordond.filmstrip.picture.heifPrimaryIsTiled
import kotlin.math.roundToInt

/**
 * How the primary image of an AVIF or HEIC is laid out, as [primaryLayout] reads it.
 */
internal sealed interface PrimaryLayout {
  /**
   * A primary image ffmpeg decodes as one stream, or a picture that is not an AVIF or HEIC.
   */
  object Single : PrimaryLayout

  /**
   * A primary image put together from coded images on a canvas of [size].
   *
   * @property size The width and height of the canvas.
   */
  sealed interface Assembled : PrimaryLayout {
    val size: Size
  }

  /**
   * A `grid`, as [heifPrimaryGrid] reads it.
   *
   * @property grid The layout.
   */
  class Grid(
    val grid: HeifGrid,
  ) : Assembled {
    override val size: Size
      get() = grid.size
  }

  /**
   * An `iovl`, as [heifPrimaryOverlay] reads it.
   *
   * @property overlay The layout.
   */
  class Overlay(
    val overlay: HeifOverlay,
  ) : Assembled {
    override val size: Size
      get() = overlay.size
  }

  /**
   * A primary image this backend does not put together.
   *
   * @property reason Why, in a sentence a person reads.
   */
  class Refused(
    val reason: String,
  ) : PrimaryLayout
}

/**
 * How the primary image of a picture whose encoded [bytes] are given, or null when this process cannot read them, is
 * laid out.
 *
 * A `grid` that [heifPrimaryGrid] refuses or an `iovl` that [heifPrimaryOverlay] refuses is refused with core's
 * reason. One derived from its inputs any other way, as [heifPrimaryIsTiled] reads it, is refused with
 * [UNASSEMBLED_REASON]. Every other picture, and one whose bytes are null, is [PrimaryLayout.Single].
 */
internal fun primaryLayout(bytes: ByteArray?): PrimaryLayout {
  if (bytes == null) return PrimaryLayout.Single
  heifPrimaryGrid(bytes)?.let { read ->
    return when (read) {
      is HeifGridRead.Read -> PrimaryLayout.Grid(read.grid)
      is HeifGridRead.Refused -> PrimaryLayout.Refused(read.reason)
    }
  }
  heifPrimaryOverlay(bytes)?.let { read ->
    return when (read) {
      is HeifOverlayRead.Read -> PrimaryLayout.Overlay(read.overlay)
      is HeifOverlayRead.Refused -> PrimaryLayout.Refused(read.reason)
    }
  }
  return if (heifPrimaryIsTiled(bytes)) PrimaryLayout.Refused(UNASSEMBLED_REASON) else PrimaryLayout.Single
}

/**
 * How [grid] is put together and run through [filter].
 *
 * Each tile is decoded as it is stored, at its own depth and subsampling, and the tiles are laid in rows on a canvas
 * the size of [HeifGrid.size], which crops the grid from its top left. The canvas takes the first tile's sample aspect
 * ratio and range and then goes through [filter].
 *
 * A grid on the [planes] route whose reading is [YcbcrReading.Identity] runs each tile through [filter] instead. Its
 * tiles decode as planar GBR, which `yuv4mpegpipe` does not carry, and the route's `mergeplanes` only renames each
 * sample's planes, so it lands the same on a tile as on the canvas.
 */
internal fun gridAssembly(
  grid: HeifGrid,
  filter: String,
  planes: PictureRoute.Planes? = null,
): Assembly {
  val merged = planes?.reading == YcbcrReading.Identity
  val tile = if (merged) filter else PASS_THROUGH
  val post = if (merged) PASS_THROUGH else filter
  return Assembly(grid.size, AssemblyLayout.Tiles(grid), tile, FrameKind.Y4m, null, { null }, null, post)
}

/**
 * How [overlay] is put together as RGB, each input run through [filter] on its own, so no input's subsampled color
 * reaches the canvas beside it.
 *
 * Each input is held to eight-bit RGB after [filter], with an alpha channel only when a fill that is not opaque shows,
 * so the canvas keeps the fill's alpha and a canvas the inputs cover whole stays opaque. The part of each input
 * [HeifOverlay.shown] says lands on the canvas is drawn at its offset in the order the overlay lists them, so a later
 * one lands over an earlier one where they meet. What no input covers is [HeifOverlay.fill] in the texels
 * [canvasTexels] gives for a picture described by [color]. The canvas takes the frame properties of the first input
 * that lands on it, or of the first input when none does.
 */
internal fun overlayAssembly(
  overlay: HeifOverlay,
  filter: String,
  color: PictureColor?,
): Assembly {
  val pin = if (overlay.fill.opaque || !overlay.fillShown) "rgb24" else "rgba"
  val read = "$filter,format=$pin"
  val layout = AssemblyLayout.Cuts(overlay.shown, overlay.inputs.first().item)
  val texels = { format: String -> canvasTexels(overlay.fill, color, format) }
  return Assembly(overlay.size, layout, read, FrameKind.Pam, null, texels, "$read,$PASS_THROUGH", PASS_THROUGH)
}

/**
 * How [overlay] is put together as samples of [canvas]'s format, each input run through [filter] on its own.
 *
 * The canvas is painted with [PlanesCanvas.codes], so no matrix ffmpeg picks touches it, and the part of each input
 * [HeifOverlay.shown] says lands on it is drawn at its offset in the order the overlay lists them. The canvas and every
 * input are tagged with one range and matrix, and the canvas goes through [post] after that.
 */
internal fun planesAssembly(
  overlay: HeifOverlay,
  filter: String,
  canvas: PlanesCanvas,
  post: String = "",
): Assembly {
  val tag = "setparams=range=${if (canvas.fullRange) "pc" else "tv"}:colorspace=bt709"
  val texels = { format: String -> canvasPlanes(format)?.let { canvas.texels(it) } }
  return Assembly(
    overlay.size,
    AssemblyLayout.Cuts(overlay.shown, unshown = null),
    "$filter,$tag",
    FrameKind.Y4m,
    canvas.format,
    texels,
    null,
    "$tag,setsar=1$post",
  )
}

/**
 * The texels [fill] paints on an RGB canvas of the ffmpeg pixel format [format], for a picture described by [color],
 * or null for a format that is not packed RGB.
 *
 * The fill is sRGB, so its red, green and blue are the codes [pictureCodesFor] gives for [color], and its alpha is kept
 * as it is, each rounded to the nearest eight-bit code. A canvas with no alpha channel leaves the alpha out.
 */
internal fun canvasTexels(
  fill: HeifCanvasFill,
  color: PictureColor?,
  format: String,
): List<ByteArray>? {
  val (red, green, blue) = pictureCodesFor(fill.red, fill.green, fill.blue, color)
  val codes = listOf(red, green, blue, fill.alpha).map { (it * BYTE_MAX / WIDE_MAX).roundToInt().toByte() }
  val texel =
    when (format) {
      "rgb24" -> codes.take(RGB)
      "rgba" -> codes
      else -> return null
    }
  return listOf(texel.toByteArray())
}

/**
 * A canvas laid as samples of [format], each plane holding one of [codes] in order, tagged with the range [fullRange]
 * gives.
 */
internal class PlanesCanvas(
  val format: String,
  val codes: IntArray,
  val fullRange: Boolean,
) {
  /**
   * One texel a plane of [planes], each code one byte for a plane of one-byte samples and two little-endian bytes past
   * that.
   */
  fun texels(planes: List<Plane>): List<ByteArray> =
    planes.mapIndexed { index, plane ->
      val code = codes[index]
      if (plane.texelBytes ==
        1
      ) {
        byteArrayOf(code.toByte())
      } else {
        byteArrayOf(code.toByte(), (code shr BYTE_BITS).toByte())
      }
    }

  companion object {
    /**
     * The canvas that paints [fill] on a picture described by [color] whose frame [route] reads. The fill's codes in
     * the picture's own color, as [pictureCodesFor] gives them, are coded the way [route] reads samples by
     * [ycbcrCodes].
     */
    fun of(
      fill: HeifCanvasFill,
      color: PictureColor?,
      route: PictureRoute.Planes,
    ): PlanesCanvas {
      val (red, green, blue) = pictureCodesFor(fill.red, fill.green, fill.blue, color)
      val codes = route.reading.ycbcrCodes(red, green, blue, route.bitDepth, route.fullRange)
      return PlanesCanvas(planarYuv(route.bitDepth), codes, route.fullRange)
    }
  }
}

internal const val UNASSEMBLED_REASON =
  "The overlay image's primary item is derived from other images in a way filmstrip does not put together on ffmpeg, " +
    "so this overlay picture cannot be drawn."

private const val RGB = 3
private const val BYTE_BITS = 8
private const val BYTE_MAX = 255.0
private const val WIDE_MAX = 65535
