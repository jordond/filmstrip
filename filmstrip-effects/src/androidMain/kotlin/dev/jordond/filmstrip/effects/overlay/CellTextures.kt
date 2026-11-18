package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.animated.CompositeResult
import dev.jordond.filmstrip.picture.animated.compositeFrames
import dev.jordond.filmstrip.picture.picturePeakBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import androidx.media3.common.util.Size as Media3Size

/**
 * The cells of a sprite sheet as bitmaps, and the cell each presentation time draws.
 *
 * Every cell is its own bitmap, cut from the sheet when the overlay resolves. The bitmaps are shared by every graph
 * that draws the overlay and none is recycled, since a later graph would be drawing a dead one. Each graph makes its
 * own textures from them through [CellTextures].
 *
 * @property bitmaps The bitmap of each cell, keyed by the rectangle it was cut from.
 * @property cell The size every cell has.
 * @property flipbook Which cell each output frame draws.
 * @property drawnStart The composition time the first frame starts at.
 * @property rate The output frame rate the frames are counted on.
 * @property luminance The HDR luminance multiplier each cell's texels are written against, for a picture whose cells
 * are mapped one at a time and so each come out with their own. Empty for a sheet, whose cells share the one multiplier
 * of the raster they are cut from.
 */
internal class CellBitmaps(
  val bitmaps: Map<PictureCrop, Bitmap>,
  val cell: Size,
  private val flipbook: Flipbook,
  private val drawnStart: Duration,
  private val rate: Int,
  private val luminance: Map<PictureCrop, Float> = emptyMap(),
) {
  /**
   * These same bitmaps drawn on the timing [drawnStart] and [rate] give, so an overlay of its own can draw a picture
   * another overlay holds without holding it again.
   */
  fun timedAs(
    drawnStart: Duration,
    rate: Int,
  ): CellBitmaps = CellBitmaps(bitmaps, cell, flipbook, drawnStart, rate, luminance)

  /**
   * The HDR luminance multiplier of the cell drawn at [presentationTimeUs], or null when the cells share one.
   */
  fun luminanceAt(presentationTimeUs: Long): Float? = luminance[shownAt(presentationTimeUs)]

  /**
   * The cell drawn at [presentationTimeUs], which the flipbook puts on its output slot first.
   */
  fun shownAt(presentationTimeUs: Long): PictureCrop =
    flipbook.cellAt(drawnStart, presentationTimeUs.microseconds, rate)
}

/**
 * One graph's textures for a sprite sheet, a texture for each cell it draws.
 *
 * A texture is made from its cell's bitmap the first time that cell is drawn and kept until [release], so a frame that
 * draws a cell it has drawn before costs a lookup. It has to be asked for on the thread that owns the graph's GL
 * context, which is where media3 asks an overlay for its texture.
 *
 * @property cells The bitmaps to make textures from.
 */
internal class CellTextures(
  private val cells: CellBitmaps,
) {
  private val textures = HashMap<PictureCrop, Int>()
  private var shownAt: Long = UNSHOWN
  private var shown: PictureCrop? = null

  /**
   * The size of the texture [textureAt] answers, which is every cell's.
   */
  val size: Media3Size = Media3Size(cells.cell.width, cells.cell.height)

  /**
   * The HDR luminance multiplier the cell drawn at [presentationTimeUs] is written against, or null when every cell
   * shares the one the overlay was built with.
   */
  fun luminanceAt(presentationTimeUs: Long): Float? = cells.luminanceAt(presentationTimeUs)

  /**
   * The texture of the cell drawn at [presentationTimeUs], made now when it has not been drawn yet.
   *
   * @throws VideoFrameProcessingException When the texture cannot be made.
   */
  fun textureAt(presentationTimeUs: Long): Int {
    val crop = cellAt(presentationTimeUs)
    textures[crop]?.let { return it }
    val made =
      try {
        GlUtil.createTexture(cells.bitmaps.getValue(crop))
      } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
      }
    textures[crop] = made
    return made
  }

  /**
   * Deletes every texture made so far.
   *
   * @throws VideoFrameProcessingException When a texture cannot be deleted. The rest are still deleted.
   */
  fun release() {
    var failure: GlUtil.GlException? = null
    for (texture in textures.values) {
      try {
        GlUtil.deleteTexture(texture)
      } catch (e: GlUtil.GlException) {
        failure = failure ?: e
      }
    }
    textures.clear()
    shown = null
    shownAt = UNSHOWN
    failure?.let { throw VideoFrameProcessingException(it) }
  }

  // media3 asks for the texture and for its size at one presentation time, so the second ask costs a comparison.
  private fun cellAt(presentationTimeUs: Long): PictureCrop {
    val held = shown
    if (held != null && presentationTimeUs == shownAt) return held
    return cells.shownAt(presentationTimeUs).also {
      shown = it
      shownAt = presentationTimeUs
    }
  }

  private companion object {
    // No frame carries this presentation time, so the first ask always chooses.
    const val UNSHOWN = Long.MIN_VALUE
  }
}

/**
 * Cuts this raster into one bitmap for each of [crops], and recycles the raster.
 *
 * The crops are rectangles of the raster as it is shown, and each bitmap is straight rather than premultiplied like the
 * raster, so media3 reads the same colors it reads from a whole picture. The raster is recycled whether or not every
 * cell is cut, and a cell already cut when one fails is recycled with it.
 *
 * @return The bitmap of each crop, in the order the crops are given.
 */
internal fun Raster.cutCells(crops: List<PictureCrop>): Map<PictureCrop, Bitmap> {
  val cut = LinkedHashMap<PictureCrop, Bitmap>(crops.size)
  var done = false
  try {
    for (crop in crops) {
      val width = crop.size.width
      val height = crop.size.height
      val argb = IntArray(width * height)
      bitmap.getPixels(argb, 0, width, crop.left, crop.top, width, height)
      cut[crop] = straightBitmap(crop.size, argb)
    }
    done = true
  } finally {
    bitmap.recycle()
    if (!done) cut.values.forEach { it.recycle() }
  }
  return cut
}

/**
 * The bytes held while a sheet of [cells] cells each [cell] in size is drawn, or null when that count does not fit in a
 * [Long].
 *
 * The cell bitmaps are held the whole time, the sheet's own raster is held beside them while they are cut, and a graph
 * that has drawn every cell holds a texture for each. The three are counted together, once for the overlay.
 *
 * @param sheet The size of the sheet as it is shown.
 */
internal fun cellsPeakBytes(
  sheet: Size,
  cell: Size,
  cells: Int,
): Long? {
  val cellBytes = bytesOf(cell.width.toLong(), cell.height.toLong(), cells.toLong())
  return picturePeakBytes(cellBytes, bytesOf(sheet.width.toLong(), sheet.height.toLong(), 1L), cellBytes)
}

/**
 * Why a sheet of [cells] cells each [cell] in size cannot be drawn through textures [maxSide] pixels on a side, or null
 * when it can.
 *
 * Only cells are uploaded, so the side is measured on the cell and a sheet longer than [maxSide] draws while its cells
 * fit.
 *
 * @param sheet The size of the sheet as it is shown.
 * @param maxSide OpenGL ES's `GL_MAX_TEXTURE_SIZE`.
 * @param ceiling The most bytes the sheet may hold at its peak, [PICTURE_PEAK_CEILING] by default.
 */
internal fun sheetCellsRefusal(
  sheet: Size,
  cell: Size,
  cells: Int,
  maxSide: Int,
  ceiling: Long = PICTURE_PEAK_CEILING,
): String? {
  if (!fitsTextureSide(cell.width, cell.height, maxSide)) return cellPastTextureSide(cell, maxSide)
  val peak = cellsPeakBytes(sheet, cell, cells)
  return if (peak == null || peak > ceiling) sheetPastCeiling(sheet, cell, cells, ceiling) else null
}

internal fun cellPastTextureSide(
  cell: Size,
  maxSide: Int,
): String =
  "A ${cell.width} by ${cell.height} cell of the sprite sheet is too long to draw. An OpenGL ES texture on this " +
    "device holds at most $maxSide pixels on a side."

internal fun sheetPastCeiling(
  sheet: Size,
  cell: Size,
  cells: Int,
  ceiling: Long,
): String =
  "The ${sheet.width} by ${sheet.height} sprite sheet is too large to draw. Its $cells cells of ${cell.width} by " +
    "${cell.height}, the sheet they are cut from and a texture for each cell must fit in $ceiling bytes."

internal fun sheetAllocationFailed(
  sheet: Size,
  cells: Int,
): String =
  "The ${sheet.width} by ${sheet.height} sprite sheet could not be drawn, since this device could not allocate its " +
    "$cells cells."

// Four bytes a texel, in a bitmap and in the texture made from it, or null when the count does not fit in a Long.
private fun bytesOf(
  width: Long,
  height: Long,
  count: Long,
): Long? =
  try {
    Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(width, height), count), TEXEL_BYTES)
  } catch (_: ArithmeticException) {
    null
  }

private const val TEXEL_BYTES = 4L

/**
 * What laying the frames of an animated picture out as cells comes to.
 */
internal sealed interface AnimatedCellsRead {
  /**
   * Every frame is a bitmap, and [cells] says which one each output frame draws.
   *
   * @property cells The bitmap of each frame, keyed by its cell of the atlas grid.
   * @property first The cell of the first frame, which stands for the overlay's own bitmap.
   * @property firstLuminance The HDR luminance multiplier the first frame is written against.
   */
  class Made(
    val cells: CellBitmaps,
    val first: PictureCrop,
    val firstLuminance: Float,
  ) : AnimatedCellsRead

  /**
   * The frames cannot be held, and [message] says why in one sentence.
   */
  class Refused(
    val message: String,
  ) : AnimatedCellsRead
}

/**
 * Composites every frame of this animated picture and makes a bitmap of each as the walk reaches it.
 *
 * Each frame is mapped for [transfer], or for an SDR frame when it is null, the way a still picture is, under the
 * color the file describes. No sample copy outlives the frame it was made for. A frame has its own HDR luminance
 * multiplier, since it is mapped on its own, and [CellBitmaps.luminanceAt] answers it. Every bitmap made is recycled
 * when a frame cannot be decoded or the device cannot allocate the next one.
 *
 * @param drawnStart The composition time the first frame starts at.
 * @param rate The output frame rate the frames are counted on.
 */
internal fun AnimatedFlipbookRead.Drawn.cellsFor(
  transfer: HdrTransfer?,
  drawnStart: Duration,
  rate: Int,
): AnimatedCellsRead {
  val canvas = picture.shown
  val bitmaps = LinkedHashMap<PictureCrop, Bitmap>(picture.frames.size)
  val luminance = HashMap<PictureCrop, Float>(picture.frames.size)
  val result =
    try {
      compositeFrames(picture, ::stillWebpSamples) { index, samples ->
        val raster = rasterOf(samples.rgba, canvas, transfer, color)
        bitmaps[grid.cells[index]] = raster.bitmap
        luminance[grid.cells[index]] = raster.hdrLuminance
      }
    } catch (_: OutOfMemoryError) {
      CompositeResult.Refused(animatedAllocationFailed(canvas, picture.frames.size))
    }
  if (result is CompositeResult.Refused) {
    bitmaps.values.forEach { it.recycle() }
    return AnimatedCellsRead.Refused(result.message)
  }
  val first = grid.cells.first()
  return AnimatedCellsRead.Made(
    CellBitmaps(bitmaps, canvas, flipbook, drawnStart, rate, luminance),
    first,
    luminance.getValue(first),
  )
}

/**
 * Why an animated picture whose frames are each [canvas] in size cannot be drawn through textures [maxSide] pixels on a
 * side, or null when it can.
 *
 * Every frame is uploaded whole, so the side is measured on the canvas.
 *
 * @param maxSide OpenGL ES's `GL_MAX_TEXTURE_SIZE`.
 */
internal fun animatedTextureRefusal(
  canvas: Size,
  maxSide: Int,
): String? =
  if (fitsTextureSide(canvas.width, canvas.height, maxSide)) {
    null
  } else {
    picturePastTextureSide(
      canvas.width,
      canvas.height,
      maxSide,
      OPEN_GL_ES,
    )
  }

internal fun animatedAllocationFailed(
  canvas: Size,
  frames: Int,
): String =
  "The $frames frames of this ${canvas.width} by ${canvas.height} animated picture could not be drawn, since this " +
    "device could not allocate a bitmap for each."
