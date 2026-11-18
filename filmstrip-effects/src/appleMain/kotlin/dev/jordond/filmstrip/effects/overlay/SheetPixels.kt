package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.animated.CompositeResult
import dev.jordond.filmstrip.picture.animated.compositeFrames
import dev.jordond.filmstrip.picture.picturePeakBytes
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIImage
import platform.Foundation.NSLock

/**
 * The cells of a sprite sheet, each one the pixels of its own rectangle of the picture.
 *
 * A cell is a view onto the bytes of the decoded sheet, so the sheet's bytes are the only copy held and nothing is
 * duplicated per cell. Cells drawn at their color make float images, and the [capacity] cells drawn most recently
 * keep theirs. A cell that is drawn again while it is kept is not converted again.
 *
 * @property cell The size every cell has.
 * @property capacity How many cells keep their float images at once.
 * @property conversions How many times a cell was brought into the kept set, which is how often one was converted.
 */
@OptIn(ExperimentalForeignApi::class)
internal class SheetPixels(
  val cell: Size,
  private val cells: Map<PictureCrop, OverlayPixels>,
  val capacity: Int,
) {
  private val lock = NSLock()
  private val recent = ArrayDeque<OverlayPixels>()

  var conversions: Int = 0
    private set

  /**
   * How many cells hold float images now.
   */
  val kept: Int get() = recent.size

  /**
   * The image of the cell at [crop] to draw onto a frame written in [colorSpace] with [transfer], or with none when
   * the frame is SDR.
   *
   * @throws PictureTooLargeToDraw When the cell's floats cannot be made.
   */
  fun enteringFrame(
    crop: PictureCrop,
    transfer: HdrTransfer?,
    colorSpace: ColorSpace,
  ): CIImage {
    val pixels = cells.getValue(crop)
    lock.lock()
    try {
      if (!recent.remove(pixels)) {
        conversions++
        if (recent.size == capacity) recent.removeFirst().dropFloats()
      }
      recent.addLast(pixels)
      return pixels.enteringFrame(transfer, colorSpace)
    } finally {
      lock.unlock()
    }
  }
}

/**
 * These pixels cut into one [SheetPixels] cell for each rectangle in [crops], or why they cannot be.
 *
 * The rectangles are of the picture as it is shown, so each is carried back through the picture's orientation onto the
 * stored texels it reads and cut there. The cell then takes the same orientation as the picture, which lays its texels
 * out exactly as the whole picture lays out that rectangle. The sheet's own image is not needed once the cells are
 * cut, since each cell holds the bytes it is a view onto.
 *
 * The count is made once for the whole overlay, by [sheetPeakBytes], and a sheet whose count passes [ceiling] is
 * refused with the reason [sheetTooLargeToDraw] gives.
 *
 * @param drawnAtColor Whether a frame may draw the cells at their color, which makes floats.
 * @param ceiling The most bytes the cells may hold at their peak, [PICTURE_PEAK_CEILING] by default.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("LongParameterList")
internal fun OverlayPixels.cutInto(
  crops: List<PictureCrop>,
  drawnAtColor: Boolean,
  bandTexels: Int = BAND_TEXELS,
  ceiling: Long = PICTURE_PEAK_CEILING,
): PictureRead<SheetPixels> {
  val cell = crops.first().size
  val peak = sheetPeakBytes(held.bytes, cell, crops.size, drawnAtColor, bandTexels, ceiling)
  if (peak == null || peak > ceiling) return PictureRead.Unsupported(sheetTooLargeToDraw(size, cell, ceiling))
  val described = color
  val cut = HashMap<PictureCrop, OverlayPixels>(crops.size)
  for (shown in crops) {
    val stored = shown.storedUnder(orientation, size, crop)
    val codes = held.cutTo(stored) ?: return PictureRead.Unsupported(SHEET_NOT_A_BITMAP)
    cut[shown] =
      OverlayPixels(codes, { described }, reader, bandTexels = bandTexels, ceiling = ceiling, orientation = orientation)
  }
  return PictureRead.Read(SheetPixels(cell, cut, floatCapacity(held.bytes, cell, crops.size, bandTexels, ceiling)))
}

/**
 * The frames of this animated picture as one [SheetPixels] cell each, in the cell of the atlas grid the flipbook names
 * for it, or why they cannot be.
 *
 * Every frame is composited once, here, and becomes the 8-bit codes of its own [OverlayPixels], or 16-bit ones when the
 * file stores them. Nothing but those codes is kept. The float image of a cell is made when it is drawn at its color,
 * and the [MIN_FLOATS_KEPT] cells drawn most recently keep theirs. The count of the whole picture was made by
 * [animatedRead] before any frame is decoded.
 */
internal fun AnimatedFlipbookRead.Drawn.framesAsPixels(): PictureRead<SheetPixels> {
  val described = color
  val cells = HashMap<PictureCrop, OverlayPixels>(picture.frames.size)
  var unreadable: PictureRead<Nothing>? = null
  val result =
    compositeFrames(picture, ::stillSamplesOf) { index, canvas ->
      if (unreadable != null) return@compositeFrames
      when (val codes = canvas.frameCodes()) {
        is PictureRead.Read -> cells[grid.cells[index]] = OverlayPixels(codes.value, { described })
        is PictureRead.Refused -> unreadable = codes
        is PictureRead.Unsupported -> unreadable = codes
        PictureRead.Unreadable -> unreadable = PictureRead.Unreadable
      }
    }
  unreadable?.let { return it }
  if (result is CompositeResult.Refused) return PictureRead.Unsupported(result.message)
  return PictureRead.Read(SheetPixels(picture.shown, cells, MIN_FLOATS_KEPT))
}

/**
 * The rectangle of the stored picture this rectangle of the shown one reads, counted from the stored picture's top
 * left corner.
 *
 * An orientation is a permutation of whole texels, so a rectangle of the shown picture is a rectangle of the one
 * stored and the two opposite corners it keeps are enough to find it. [clap] is the cut made before the turn.
 */
private fun PictureCrop.storedUnder(
  orientation: PictureOrientation,
  shown: Size,
  clap: PictureCrop?,
): PictureCrop {
  val first = orientation.sourceOf(left, top, shown)
  val last = orientation.sourceOf(left + size.width - 1, top + size.height - 1, shown)
  val width = if (orientation.swapsAxes) size.height else size.width
  val height = if (orientation.swapsAxes) size.width else size.height
  return PictureCrop(
    minOf(first.x, last.x) + (clap?.left ?: 0),
    minOf(first.y, last.y) + (clap?.top ?: 0),
    Size(width, height),
  )
}

/**
 * The bytes drawing a sheet's cells holds at its peak, or null when that count does not fit in a [Long].
 *
 * The decoded sheet's [held] bytes stay held and every cell is a view onto them. A frame that draws at the picture's
 * color reads a cell back a band at a time and makes its floats, and [floatCapacity] cells keep theirs.
 */
internal fun sheetPeakBytes(
  held: Long?,
  cell: Size,
  cells: Int,
  drawnAtColor: Boolean,
  bandTexels: Int = BAND_TEXELS,
  ceiling: Long = PICTURE_PEAK_CEILING,
): Long? {
  if (!drawnAtColor) return picturePeakBytes(held, band = 0L, made = 0L)
  val band = drawnBandBytes(cell.width.toLong(), cell.height.toLong(), bandTexels)
  val floats =
    cell.width.toLong() * cell.height * FLOAT_TEXEL_BYTES * floatCapacity(held, cell, cells, bandTexels, ceiling)
  return picturePeakBytes(held, band, floats)
}

/**
 * How many cells keep their float images at once, which is every cell when they fit under [ceiling] beside the bytes
 * held anyway and otherwise as many as fit, and never fewer than [MIN_FLOATS_KEPT] or more than there are [cells].
 */
internal fun floatCapacity(
  held: Long?,
  cell: Size,
  cells: Int,
  bandTexels: Int = BAND_TEXELS,
  ceiling: Long = PICTURE_PEAK_CEILING,
): Int {
  val band =
    drawnBandBytes(cell.width.toLong(), cell.height.toLong(), bandTexels) ?: return minOf(cells, MIN_FLOATS_KEPT)
  val room = ceiling - (held ?: 0L) - band
  val fit = room / (cell.width.toLong() * cell.height * FLOAT_TEXEL_BYTES)
  return minOf(cells.toLong(), maxOf(MIN_FLOATS_KEPT.toLong(), fit)).toInt()
}

/**
 * The reason a sheet shown at [sheet] is refused when drawing its [cell]s at their color holds more than [ceiling]
 * bytes.
 */
internal fun sheetTooLargeToDraw(
  sheet: Size,
  cell: Size,
  ceiling: Long,
): String =
  "The ${sheet.width} by ${sheet.height} sprite sheet is too large to draw at its color. Its decoded bytes, the " +
    "band read back from a ${cell.width} by ${cell.height} cell and the floats of at least $MIN_FLOATS_KEPT cells " +
    "must fit in $ceiling bytes."

/**
 * The reason a sheet is refused when its decoded picture is not a bitmap its cells can be cut from.
 */
internal const val SHEET_NOT_A_BITMAP = "The sprite sheet's decoded picture holds no bitmap to cut its cells from."

/**
 * The fewest cells that keep the float image they are drawn from, so the cell drawn last and the one before it never
 * have to be converted again at once.
 */
internal const val MIN_FLOATS_KEPT: Int = 2
