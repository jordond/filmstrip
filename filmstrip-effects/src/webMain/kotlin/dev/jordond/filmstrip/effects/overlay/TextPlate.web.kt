@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.toByteArray
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsException
import kotlin.js.thrownValue

/**
 * The most pixels a side of one canvas [textCoverage] draws on holds.
 *
 * Older iOS Safari caps a canvas at 16,777,216 pixels in all, the smallest area any browser allows, which a 4096 by
 * 4096 canvas fills exactly. A tile of 2048 by 2048 stays a quarter of that cap, so no tile reaches any browser's
 * canvas limit.
 */
internal const val TEXT_TILE_SIDE: Int = 2048

/**
 * The part of a raster one tile of [textCoverage] holds, in raster pixels from the top left.
 *
 * [right] and [bottom] are one past the last column and row the tile holds.
 */
internal class TextTile(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
)

/**
 * Draws glyph coverage with [draw] over a [width] by [height] raster and reads it back, four bytes a texel row by row
 * from the top left, with the coverage in each texel's alpha.
 *
 * The raster is drawn in tiles at most [tileSide] on a side. Each tile is its own canvas, translated so [draw] places
 * the text in raster coordinates, and is set to nothing once its texels are read or drawing it throws. [draw] is handed
 * the [TextTile] it draws into, so it can leave out what lands wholly outside it. Each canvas starts transparent and
 * [draw] fills in [ink]'s colour at full alpha, the fill it is handed. A browser shapes a glyph's coverage by the
 * colour it is drawn in, so each texel's alpha is how much of it the glyphs cover in the text's own colour, whatever
 * alpha the text is laid at. A canvas reads its texels back straight, so a colour font's glyph keeps its own colour in
 * them.
 *
 * The raster is counted by [textRasterFits] at [TEXT_TEXEL_BYTES] a texel. The band is the copy wasm makes of the
 * floats an HDR frame uploads, at [FLOAT_TEXEL_BYTES] a texel, which js uploads in place and counts all the same.
 * Drawing peaks below that, at the coverage, one tile and its read, then the samples made from the coverage. A raster
 * past [ceiling] is refused with the reason [textTooLargeToDraw] gives before anything is made, and then one longer
 * than [maxSide] on a side with the reason [textPastTextureSide] gives. The sides narrow to [Int] only once they fit.
 * A tile canvas with no 2D context, or a tile read the browser cannot allocate, is refused with the reason
 * [textAllocationFailed] gives for the whole raster, and so is coverage js cannot allocate, as [allocatedOrNull] says.
 *
 * @param ink The text colour, straight ARGB. Its alpha is not drawn.
 * @param maxSide The most pixels a texture holds on a side, WebGL's `MAX_TEXTURE_SIZE`, the limit of `texImage2D` on
 * this backend.
 * @param ceiling The most bytes the raster may hold at its peak, [PICTURE_PEAK_CEILING] unless a test sets it.
 * @param tileSide The most pixels a tile holds on a side, [TEXT_TILE_SIDE] unless a test sets it.
 * @param tileCanvas Makes the canvas a tile of the given width and height is drawn on, a new [OffscreenCanvas] unless a
 * test sets it.
 * @return The coverage read back, or why there is none.
 */
internal fun textCoverage(
  width: Long,
  height: Long,
  ink: Int,
  maxSide: Int,
  ceiling: Long = PICTURE_PEAK_CEILING,
  tileSide: Int = TEXT_TILE_SIDE,
  tileCanvas: (Int, Int) -> OffscreenCanvas = { tileWidth, tileHeight -> OffscreenCanvas(tileWidth, tileHeight) },
  draw: (OverlayContext2D, TextTile) -> Unit,
): TextRaster.Made<ByteArray> {
  if (!textRasterFits(width, height, TEXT_TEXEL_BYTES, uploadCopyBytes(width, height), ceiling)) {
    return TextRaster.Refused(textTooLargeToDraw(width, height, TEXT_TEXEL_BYTES, ceiling))
  }
  val wide = width.toInt()
  val tall = height.toInt()
  if (!fitsTextureSide(wide, tall, maxSide)) {
    return TextRaster.Refused(textPastTextureSide(wide, tall, maxSide, WEBGL))
  }

  val bytes = width * height * RGBA_CHANNELS
  val coverage =
    (if (bytes <= Int.MAX_VALUE) allocatedOrNull { ByteArray(bytes.toInt()) } else null)
      ?: return unallocated(wide, tall)
  for (top in 0 until tall step tileSide) {
    for (left in 0 until wide step tileSide) {
      val tileWidth = minOf(tileSide, wide - left)
      val tileHeight = minOf(tileSide, tall - top)
      val tile = tileCanvas(tileWidth, tileHeight)
      val read =
        try {
          val context = tile.getContext(CONTEXT_2D) ?: return unallocated(wide, tall)
          context.fillStyle = opaqueCssOf(ink)
          context.translate(-left.toDouble(), -top.toDouble())
          draw(context, TextTile(left, top, left + tileWidth, top + tileHeight))
          allocatedOrNull {
            context
              .getImageData(0, 0, tileWidth, tileHeight)
              .data.buffer
              .toByteArray()
          } ?: return unallocated(wide, tall)
        } finally {
          tile.width = 0
          tile.height = 0
        }

      val rowBytes = tileWidth * RGBA_CHANNELS
      for (row in 0 until tileHeight) {
        val at = ((top + row) * wide + left) * RGBA_CHANNELS
        read.copyInto(coverage, at, row * rowBytes, (row + 1) * rowBytes)
      }
    }
  }
  return TextRaster.Drawn(coverage)
}

/**
 * Runs [make], which allocates an array, and gives null when the browser cannot allocate it.
 *
 * A typed array the browser cannot allocate throws a RangeError, which js hands on as it is and wasm wraps in a
 * JsException. Only a RangeError is caught, and anything else [make] throws is thrown on. On js a Kotlin array is a
 * typed array, so one too large comes back null too. On wasm only an array the browser makes, such as a canvas read,
 * does, since a Kotlin array lives in wasm's own memory.
 */
internal fun <T : Any> allocatedOrNull(make: () -> T): T? =
  try {
    make()
  } catch (thrown: JsException) {
    // wasm's limit: a Kotlin array wasm cannot allocate traps rather than throws, and nothing can catch a trap.
    if (thrown.thrownValue is RangeError) null else throw thrown
  }

// What JavaScript throws for a length it cannot allocate, read only to tell an allocation failing from anything else.
private external class RangeError : JsAny

private fun unallocated(
  width: Int,
  height: Int,
): TextRaster.Refused = TextRaster.Refused(textAllocationFailed(width, height))

// The floats are held while their copy is made, and nothing else in the upload is as large.
private fun uploadCopyBytes(
  width: Long,
  height: Long,
): Long? {
  if (width !in 0..Int.MAX_VALUE || height !in 0..Int.MAX_VALUE) return null
  val texels = width * height
  return if (texels > Long.MAX_VALUE / FLOAT_TEXEL_BYTES) null else texels * FLOAT_TEXEL_BYTES
}

private fun opaqueCssOf(argb: Int): String =
  RGB_SHIFTS.joinToString(prefix = "rgb(", postfix = ")") { "${(argb ushr it) and BYTE_MASK}" }

private const val RGBA_CHANNELS = 4
private val RGB_SHIFTS = intArrayOf(16, 8, 0)
private const val BYTE_MASK = 0xFF
