package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.HeifStills.int32
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sprite sheets for testing a flipbook, built in Kotlin and written as PNG with no binary in the tree.
 *
 * Every sheet is a grid of [COLUMNS] by [ROWS] cells, each [CELL] in size, so a whole sheet is [SHEET]. A cell is a
 * flat color from [colorOf] with its index written as eight squares, black for a clear bit and white for a set one,
 * most significant bit first. The outermost ring of texels of every cell is [RING], a color no cell's interior holds,
 * so a texel that bled in from a neighbouring cell shows up. [indexOf] reads the index back out of a cell's texels.
 */
@InternalFilmstripApi
public object SpriteSheets {
  /**
   * The number of cells in a row.
   */
  public const val COLUMNS: Int = 4

  /**
   * The number of rows of cells.
   */
  public const val ROWS: Int = 3

  /**
   * The size of one cell, in texels.
   */
  public val CELL: Size = Size(32, 16)

  /**
   * The size of a whole sheet, in texels.
   */
  public val SHEET: Size = Size(CELL.width * COLUMNS, CELL.height * ROWS)

  /**
   * The packed ARGB color of the ring of texels around every cell.
   */
  public const val RING: Int = 0xFFFF00FF.toInt()

  /**
   * A sheet whose width [COLUMNS] does not divide, painted one flat color.
   */
  public val UNDIVISIBLE: Size = Size(SHEET.width + 2, SHEET.height)

  private val UNEVEN_PATTERN: List<Duration> =
    listOf(100.milliseconds, 30.milliseconds, 250.milliseconds, 40.milliseconds, 70.milliseconds)

  /**
   * One duration per cell of a full sheet, a repeating 100, 30, 250, 40 and 70 ms.
   *
   * It is the list every backend's uneven test builds its sheet with.
   */
  public val UNEVEN: List<Duration> = List(COLUMNS * ROWS) { UNEVEN_PATTERN[it % UNEVEN_PATTERN.size] }

  private val COLORS: List<Int> =
    listOf(
      0xFFE02020,
      0xFF20C020,
      0xFF2040E0,
      0xFFE0E020,
      0xFF20E0E0,
      0xFFE08020,
      0xFF8020E0,
      0xFF808080,
      0xFFA04040,
      0xFF40A040,
      0xFF4040A0,
      0xFFA0A040,
    ).map { it.toInt() }

  private const val BITS = 8
  private const val SQUARE_STEP = 3
  private const val SQUARE_LEFT = 2
  private const val SQUARE_ROW = 7
  private const val BYTE = 0xFF
  private const val RED_SHIFT = 16
  private const val GREEN_SHIFT = 8
  private const val FLAT_TOLERANCE = 3
  private const val EIGHT_BITS: Byte = 8
  private const val SIXTEEN_BITS: Byte = 16
  private const val RGBA: Byte = 6

  /**
   * The packed ARGB color the interior of cell [index] is painted, outside the squares that write the index.
   */
  public fun colorOf(index: Int): Int = COLORS[index]

  /**
   * The texels of cell [index] as packed ARGB, row by row.
   */
  public fun cellPixels(index: Int): IntArray {
    val texels = IntArray(CELL.width * CELL.height) { colorOf(index) }
    for (row in 0 until CELL.height) {
      for (column in 0 until CELL.width) {
        if (row == 0 || column == 0 || row == CELL.height - 1 || column == CELL.width - 1) {
          texels[row * CELL.width + column] = RING
        }
      }
    }
    for (bit in 0 until BITS) {
      val set = (index shr (BITS - 1 - bit)) and 1 == 1
      for (row in SQUARE_ROW..SQUARE_ROW + 1) {
        for (column in squareLeft(bit)..squareLeft(bit) + 1) {
          texels[row * CELL.width + column] = if (set) WHITE else BLACK
        }
      }
    }
    return texels
  }

  /**
   * The texels of a whole sheet as packed ARGB, row by row.
   *
   * Cells `0 until frames` are painted and the rest are transparent.
   *
   * @throws IllegalArgumentException when [frames] is outside `1..12`.
   */
  public fun pixels(frames: Int = COLUMNS * ROWS): IntArray {
    require(frames in 1..COLUMNS * ROWS) { "A sheet holds 1 to ${COLUMNS * ROWS} frames, not $frames." }
    val texels = IntArray(SHEET.width * SHEET.height)
    for (index in 0 until frames) {
      val cell = cellPixels(index)
      val left = (index % COLUMNS) * CELL.width
      val top = (index / COLUMNS) * CELL.height
      for (row in 0 until CELL.height) {
        cell.copyInto(texels, (top + row) * SHEET.width + left, row * CELL.width, (row + 1) * CELL.width)
      }
    }
    return texels
  }

  /**
   * The index written in a cell's texels, read off the middle of each of its eight squares.
   *
   * @param cell The texels of one cell as packed ARGB, row by row, [CELL] in size.
   * @throws IllegalArgumentException when [cell] is not [CELL] in size or a square holds neither black nor white.
   */
  public fun indexOf(cell: IntArray): Int {
    requireCell(cell)
    return CounterCells.indexOf(cell)
  }

  /**
   * How many texels on the outermost ring of [cell] are not [RING], which is how many texels bled in from beside it.
   *
   * It reads a cell cut out at its own size and cannot see a bilinear bleed. A neighbouring cell's outermost texel is
   * also [RING], so a texel that bleeds in from one texel away is the color it replaces. Use [flatPng] and
   * [foreignTexels] to look for bleed in a cell drawn at any scale.
   *
   * @param cell The texels of one cell as packed ARGB, row by row, [CELL] in size.
   */
  public fun bledTexels(cell: IntArray): Int {
    requireCell(cell)
    var bled = 0
    for (row in 0 until CELL.height) {
      for (column in 0 until CELL.width) {
        val onRing = row == 0 || column == 0 || row == CELL.height - 1 || column == CELL.width - 1
        if (onRing && cell[row * CELL.width + column] != RING) bled++
      }
    }
    return bled
  }

  /**
   * An eight-bit RGBA PNG of a sheet holding [frames] frames.
   *
   * @throws IllegalArgumentException when [frames] is outside `1..12`.
   */
  public fun png(frames: Int = COLUMNS * ROWS): ByteArray = rgbaPng(SHEET, pixels(frames), EIGHT_BITS)

  /**
   * A sixteen-bit RGBA PNG of a full sheet, each eight-bit sample widened to 16 bits.
   */
  public fun sixteenBitPng(): ByteArray = rgbaPng(SHEET, pixels(), SIXTEEN_BITS)

  /**
   * A full sheet stored turned a quarter turn and tagged with Exif orientation 6, so it is shown as [SHEET].
   *
   * The stored picture is [SHEET]'s height wide and its width high.
   */
  public fun orientedPng(): ByteArray {
    val shown = pixels()
    val stored = Size(SHEET.height, SHEET.width)
    val texels = IntArray(stored.width * stored.height)
    for (y in 0 until stored.height) {
      for (x in 0 until stored.width) {
        texels[y * stored.width + x] = shown[x * SHEET.width + (stored.height - 1 - y)]
      }
    }
    return OrientedPictures.taggedPng(rgbaPng(stored, texels, EIGHT_BITS), ORIENTATION_CLOCKWISE)
  }

  /**
   * The texels of a full sheet whose every cell is its [colorOf] color out to its edges, with no ring and no squares.
   *
   * A texel drawn from beside a cell is then a color the cell does not hold, whichever way the cell is scaled.
   */
  public fun flatPixels(): IntArray {
    val texels = IntArray(SHEET.width * SHEET.height)
    for (y in 0 until SHEET.height) {
      for (x in 0 until SHEET.width) {
        texels[y * SHEET.width + x] = colorOf(y / CELL.height * COLUMNS + x / CELL.width)
      }
    }
    return texels
  }

  /**
   * An eight-bit RGBA PNG of [flatPixels], untagged so its codes are the colors [colorOf] gives.
   */
  public fun flatPng(): ByteArray = rgbaPng(SHEET, flatPixels(), EIGHT_BITS)

  /**
   * How many drawn texels are not cell [index]'s flat color at some coverage over black, which is how many hold a
   * color that bled in from beside it.
   *
   * @param frame The drawn texels as packed ARGB, whose alpha is ignored. A texel of no color is skipped, since it is
   * not covered by the cell.
   * @param tolerance How far a channel may sit from the color at its coverage, in codes.
   */
  public fun foreignTexels(
    frame: IntArray,
    index: Int,
    tolerance: Int = FLAT_TOLERANCE,
  ): Int {
    val own = colorOf(index)
    val channels = intArrayOf(own shr RED_SHIFT and BYTE, own shr GREEN_SHIFT and BYTE, own and BYTE)
    val widest = channels.indices.maxBy { channels[it] }
    var foreign = 0
    for (texel in frame) {
      val read = intArrayOf(texel shr RED_SHIFT and BYTE, texel shr GREEN_SHIFT and BYTE, texel and BYTE)
      if (read.all { it == 0 }) continue
      val coverage = read[widest].toFloat() / channels[widest]
      if (channels.indices.any { abs(channels[it] * coverage - read[it]) > tolerance }) foreign++
    }
    return foreign
  }

  /**
   * A PNG of [UNDIVISIBLE] painted one flat color.
   */
  public fun undivisiblePng(): ByteArray = OverlayPixels.pngOf(UNDIVISIBLE, COLORS[0])

  private const val ORIENTATION_CLOCKWISE = 6
  private const val WHITE = 0xFFFFFFFF.toInt()
  private const val BLACK = 0xFF000000.toInt()

  private fun requireCell(cell: IntArray) {
    val texels = CELL.width * CELL.height
    require(cell.size == texels) { "A cell holds $texels texels, not ${cell.size}." }
  }

  private fun squareLeft(bit: Int): Int = SQUARE_LEFT + SQUARE_STEP * bit

  private fun rgbaPng(
    size: Size,
    texels: IntArray,
    bitDepth: Byte,
  ): ByteArray {
    val wide = bitDepth == SIXTEEN_BITS
    val rows = ArrayList<Byte>()
    for (row in 0 until size.height) {
      rows += 0
      for (column in 0 until size.width) {
        val texel = texels[row * size.width + column]
        for (shift in intArrayOf(16, 8, 0, 24)) {
          val sample = (texel shr shift) and 0xFF
          if (wide) rows += sample.toByte()
          rows += sample.toByte()
        }
      }
    }
    val header = int32(size.width) + int32(size.height) + byteArrayOf(bitDepth, RGBA, 0, 0, 0)
    return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
      OverlayPixels.chunk("IHDR", header) +
      OverlayPixels.chunk("IDAT", OverlayPixels.zlibStored(rows.toByteArray())) +
      OverlayPixels.chunk("IEND", ByteArray(0))
  }
}
