package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * The operation lists every animated picture fixture is written from, and the painter that says what each frame of one
 * shows.
 *
 * An [Op] is one frame, a [Tile] laid at a place on the canvas with a disposal, a blend and a stored delay. The same
 * list drives [AnimatedGifs], [AnimatedApngs] and [AnimatedWebps], so every container is written from one truth.
 * [canvases] paints a list texel by texel with no code from core, and its answer is the canvas each frame shows.
 * Every list keeps its places on even texels, so a WebP can lay it.
 */
@InternalFilmstripApi
public object AnimatedPictures {
  /**
   * What a frame leaves behind once it has been shown.
   */
  @InternalFilmstripApi
  public enum class Disposal { None, Background, Previous }

  /**
   * How a frame's texels meet the canvas under them.
   */
  @InternalFilmstripApi
  public enum class Blend { Source, Over }

  /**
   * A rectangle of packed ARGB texels, with the hex of the lossless WebP chunks that hold the same texels.
   *
   * @property name What the tile is, for test messages.
   * @property width The width in texels.
   * @property height The height in texels.
   */
  @InternalFilmstripApi
  public class Tile internal constructor(
    public val name: String,
    public val width: Int,
    public val height: Int,
    private val paint: (x: Int, y: Int) -> Int,
    internal val webp: String?,
  ) {
    /**
     * The packed ARGB texel at [x] and [y].
     */
    public fun texel(
      x: Int,
      y: Int,
    ): Int = paint(x, y)

    /**
     * Whether any texel is fully transparent.
     */
    public val hasHole: Boolean
      get() = (0 until height).any { y -> (0 until width).any { x -> texel(x, y) ushr 24 == 0 } }

    /**
     * Whether every texel is fully opaque or fully transparent, which a GIF can hold.
     */
    public val isBinaryAlpha: Boolean
      get() = (0 until height).all { y -> (0 until width).all { x -> texel(x, y) ushr 24 in listOf(0, 255) } }

    override fun toString(): String = name
  }

  /**
   * One frame of an animation.
   *
   * @property x The left of the frame on the canvas.
   * @property y The top of the frame on the canvas.
   * @property tile The texels the frame holds.
   * @property disposal What the frame leaves behind.
   * @property blend How the frame meets the canvas.
   * @property delayMillis The delay stored with the frame, in milliseconds.
   */
  @InternalFilmstripApi
  public class Op(
    public val x: Int,
    public val y: Int,
    public val tile: Tile,
    public val disposal: Disposal = Disposal.None,
    public val blend: Blend = Blend.Over,
    public val delayMillis: Int = FRAME_MILLIS,
  ) {
    override fun toString(): String = "${tile.name} at $x,$y $disposal $blend"
  }

  /**
   * A stored APNG delay, a numerator over a denominator.
   *
   * @property numerator The numerator, in units of the denominator.
   * @property denominator The denominator, where zero stands for 100.
   */
  @InternalFilmstripApi
  public class Fraction(
    public val numerator: Int,
    public val denominator: Int,
  ) {
    /**
     * The delay the fraction stores, in milliseconds.
     */
    public val storedMillis: Int get() = numerator * MILLIS_PER_SECOND / (if (denominator == 0) 100 else denominator)

    override fun toString(): String = "$numerator/$denominator"
  }

  /**
   * The tiles the lists lay.
   */
  @InternalFilmstripApi
  public object Tiles {
    /**
     * Sixteen by twelve, opaque red.
     */
    public val RED: Tile = flat("red", 16, 12, 0xFFE02020.toInt(), WebpTileChunks.RED)

    /**
     * Twelve by twelve, opaque green.
     */
    public val GREEN: Tile = flat("green", 12, 12, 0xFF20C020.toInt(), WebpTileChunks.GREEN)

    /**
     * Ten by ten, opaque yellow.
     */
    public val YELLOW: Tile = flat("yellow", 10, 10, 0xFFE0E020.toInt(), WebpTileChunks.YELLOW)

    /**
     * Eight by eight, blue at half alpha.
     */
    public val BLUE_HALF: Tile = flat("blue half", 8, 8, 0x802040E0.toInt(), WebpTileChunks.BLUE_HALF)

    /**
     * Twelve by twelve green with a transparent hole four by four at its centre.
     */
    public val GREEN_HOLE: Tile =
      Tile(
        "green hole",
        12,
        12,
        { x, y -> if (x in 4..7 && y in 4..7) 0 else 0xFF20C020.toInt() },
        WebpTileChunks.GREEN_HOLE,
      )

    /**
     * Thirty-two by twenty-four, opaque cyan, the whole of [CANVAS]. It has no WebP.
     */
    public val FULL: Tile = flat("full", CANVAS_WIDTH, CANVAS_HEIGHT, 0xFF20E0E0.toInt(), null)

    /**
     * Thirty-two by sixteen with the index [index] written as eight squares, black for a clear bit and white for a set
     * one, most significant bit first, on a flat blue.
     *
     * The squares sit where [CounterCells.indexOf] reads them, and the frames of a [COUNTERS] file are these.
     *
     * @throws IllegalArgumentException when [index] is outside `0..59`.
     */
    public fun counter(index: Int): Tile {
      require(index in 0 until COUNTER_FRAMES) { "A counter tile is 0 to ${COUNTER_FRAMES - 1}, not $index." }
      return Tile(
        "counter $index",
        COUNTER_WIDTH,
        COUNTER_HEIGHT,
        { x, y -> CounterCells.texel(index, x, y) },
        WebpTileChunks.COUNTERS[index],
      )
    }

    /**
     * A tile painted from the texels of a lossy WebP still, which are what libwebp decodes it to.
     */
    public fun lossy(still: LossyWebpStill): Tile {
      val truth = still.truth()

      fun channel(
        x: Int,
        y: Int,
        offset: Int,
      ): Int = truth[(y * still.width + x) * 4 + offset].toInt() and BYTE_MASK
      return Tile(
        "lossy ${still.name}",
        still.width,
        still.height,
        { x, y ->
          (channel(x, y, 3) shl 24) or (channel(x, y, 0) shl 16) or (channel(x, y, 1) shl 8) or channel(x, y, 2)
        },
        still.frame,
      )
    }

    /**
     * A [width] by [height] tile of [argb], which only the formats that need no WebP of their own can hold.
     */
    public fun solid(
      width: Int,
      height: Int,
      argb: Int,
    ): Tile = flat("solid ${argb.toUInt().toString(HEX)}", width, height, argb, null)

    /**
     * A [width] by [height] tile of unrelated 32-bit texels, which no deflater shrinks. [seed] changes every texel.
     */
    public fun noisy(
      width: Int,
      height: Int,
      seed: Int,
    ): Tile =
      Tile("noisy $seed", width, height, { x, y ->
        var h = seed * -1640531535 + x * -2048144777 + y * -1028477379
        h = (h xor (h ushr 15)) * 739982445
        h xor (h ushr 12)
      }, null)

    private fun flat(
      name: String,
      width: Int,
      height: Int,
      argb: Int,
      webp: String?,
    ): Tile = Tile(name, width, height, { _, _ -> argb }, webp)
  }

  /**
   * The canvas of every list but [COUNTERS].
   */
  public val CANVAS: Size = Size(CANVAS_WIDTH, CANVAS_HEIGHT)

  /**
   * The canvas of [counterOps].
   */
  public val COUNTER_CANVAS: Size = Size(96, 96)

  /**
   * The size of every counter tile, which is also the cell [CounterCells.indexOf] reads.
   */
  public val COUNTER_CELL: Size = Size(COUNTER_WIDTH, COUNTER_HEIGHT)

  /**
   * The number of frames in a counter file.
   */
  public const val COUNTER_FRAMES: Int = 60

  /**
   * Sub-rectangle frames, a transparent hole, `None` and `Background` disposal and a `Background` frame with no
   * transparent texel, all blended `Over`, so any container can hold it.
   */
  public val SHARED: List<Op> =
    listOf(
      Op(0, 0, Tiles.RED),
      Op(8, 6, Tiles.GREEN_HOLE),
      Op(20, 4, Tiles.GREEN_HOLE, Disposal.Background),
      Op(4, 4, Tiles.GREEN_HOLE),
      Op(10, 8, Tiles.GREEN, Disposal.Background),
      Op(16, 12, Tiles.RED),
    )

  /**
   * `Previous` disposal on the first frame and in the middle, blended `Over`. A WebP has no `Previous`.
   */
  public val WITH_PREVIOUS: List<Op> =
    listOf(
      Op(0, 0, Tiles.RED, Disposal.Previous),
      Op(4, 4, Tiles.YELLOW),
      Op(8, 8, Tiles.GREEN, Disposal.Previous),
      Op(16, 12, Tiles.RED),
      Op(2, 2, Tiles.YELLOW, Disposal.Previous),
      Op(6, 6, Tiles.GREEN_HOLE),
    )

  /**
   * `Source` blends, partial alpha, and a `Source` frame whose hole replaces what is under it. A GIF has no blend.
   */
  public val WITH_SOURCE: List<Op> =
    listOf(
      Op(0, 0, Tiles.RED, blend = Blend.Source),
      Op(4, 4, Tiles.BLUE_HALF, blend = Blend.Source),
      Op(8, 4, Tiles.GREEN_HOLE, blend = Blend.Source),
      Op(24, 16, Tiles.BLUE_HALF),
      Op(12, 10, Tiles.YELLOW, Disposal.Background, Blend.Source),
      Op(0, 12, Tiles.RED),
    )

  /**
   * A list whose first frame is the whole canvas, so an APNG can make it its default image.
   */
  public val DEFAULT_IS_FRAME: List<Op> =
    listOf(
      Op(0, 0, Tiles.FULL),
      Op(4, 4, Tiles.RED),
      Op(10, 10, Tiles.YELLOW, Disposal.Background),
    )

  /**
   * A lossy WebP frame with alpha, one of odd width, and a lossless one.
   */
  public val LOSSY_FRAMES: List<Op> =
    listOf(
      Op(0, 0, Tiles.lossy(LossyWebpStills.EVEN)),
      Op(10, 8, Tiles.lossy(LossyWebpStills.ODD_WIDTH), Disposal.Background, Blend.Source),
      Op(4, 12, Tiles.RED),
    )

  /**
   * The delays a GIF stores, in milliseconds, whole centiseconds.
   */
  public val GIF_DELAYS: List<Int> = listOf(0, 10, 20, 30, 250)

  /**
   * The delays an APNG or a WebP stores, in milliseconds.
   */
  public val STORED_DELAYS: List<Int> = listOf(0, 8, 10, 20, 250)

  /**
   * The APNG delays whose denominator is zero or that are not in thousandths.
   */
  public val FRACTIONS: List<Fraction> = listOf(Fraction(5, 0), Fraction(0, 0), Fraction(3, 100))

  /**
   * One red frame for each of [delays], stored with it.
   */
  public fun delayOps(delays: List<Int>): List<Op> = delays.map { Op(0, 0, Tiles.RED, delayMillis = it) }

  /**
   * [count] frames of [COUNTER_CANVAS], frame `n` laid at the top left as `counter(n)`.
   *
   * @throws IllegalArgumentException when [count] is outside `1..60`.
   */
  public fun counterOps(count: Int = COUNTER_FRAMES): List<Op> {
    require(count in 1..COUNTER_FRAMES) { "A counter file holds 1 to $COUNTER_FRAMES frames, not $count." }
    return List(count) { Op(0, 0, Tiles.counter(it)) }
  }

  /**
   * The canvas each frame of [ops] shows once it is drawn, as packed ARGB row by row.
   *
   * It starts transparent. Before a frame is drawn the one ahead of it is disposed, `Background` clearing its region to
   * transparent and `Previous` giving back the canvas as it was before that frame, which on the first frame is
   * transparent. `Source` replaces texels, hole included, and `Over` lays straight alpha source over the canvas.
   */
  public fun canvases(
    canvas: Size,
    ops: List<Op>,
  ): List<IntArray> {
    var current = IntArray(canvas.width * canvas.height)
    var before = current.copyOf()
    var previous: Op? = null
    val shown = mutableListOf<IntArray>()
    for (op in ops) {
      when (previous?.disposal) {
        Disposal.Background -> forEachTexel(previous) { cx, cy, _ -> current[cy * canvas.width + cx] = 0 }
        Disposal.Previous -> current = before.copyOf()
        else -> Unit
      }
      before = current.copyOf()
      forEachTexel(op) { cx, cy, texel ->
        val at = cy * canvas.width + cx
        current[at] = if (op.blend == Blend.Source) texel else over(texel, current[at])
      }
      shown += current.copyOf()
      previous = op
    }
    return shown
  }

  private inline fun forEachTexel(
    op: Op,
    visit: (x: Int, y: Int, texel: Int) -> Unit,
  ) {
    for (ty in 0 until op.tile.height) {
      for (tx in 0 until op.tile.width) visit(op.x + tx, op.y + ty, op.tile.texel(tx, ty))
    }
  }

  private fun over(
    source: Int,
    under: Int,
  ): Int {
    val sa = source ushr 24
    val ua = under ushr 24
    if (sa == BYTE_MASK) return source
    if (sa == 0) return under
    val s = sa / BYTE_MASK.toDouble()
    val u = ua / BYTE_MASK.toDouble()
    val a = s + u * (1 - s)

    fun channel(shift: Int): Int {
      val value = (((source shr shift) and BYTE_MASK) * s + ((under shr shift) and BYTE_MASK) * u * (1 - s)) / a
      return (value + 0.5).toInt()
    }
    return ((a * BYTE_MASK + 0.5).toInt() shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }

  internal const val FRAME_MILLIS: Int = 40
  internal const val MILLIS_PER_SECOND: Int = 1000
}

private const val HEX = 16
private const val CANVAS_WIDTH = 32
private const val CANVAS_HEIGHT = 24
private const val COUNTER_WIDTH = 32
private const val COUNTER_HEIGHT = 16

/**
 * The squares a counter tile writes its index in.
 */
@InternalFilmstripApi
public object CounterCells {
  private const val BITS = 8
  private const val SQUARE_STEP = 3
  private const val SQUARE_LEFT = 2
  private const val SQUARE_ROW = 7
  private const val RGB = 0xFFFFFF
  private const val BACKGROUND = 0xFF2040E0.toInt()

  /**
   * The packed ARGB texel of counter [index] at [x] and [y] in its cell.
   */
  public fun texel(
    index: Int,
    x: Int,
    y: Int,
  ): Int {
    for (bit in 0 until BITS) {
      val left = SQUARE_LEFT + bit * SQUARE_STEP
      if (y in SQUARE_ROW..SQUARE_ROW + 1 && x in left..left + 1) {
        return if ((index shr (BITS - 1 - bit)) and 1 == 1) WHITE else BLACK
      }
    }
    return BACKGROUND
  }

  /**
   * The index written in a cell's texels, read off the middle of each of its eight squares.
   *
   * @param cell The texels of one cell as packed ARGB, row by row, [AnimatedPictures.COUNTER_CELL] in size.
   * @throws IllegalArgumentException when [cell] is not that size or a square holds neither black nor white.
   */
  public fun indexOf(cell: IntArray): Int {
    val size = AnimatedPictures.COUNTER_CELL
    require(cell.size == size.width * size.height) { "A counter cell is ${size.width}x${size.height} texels." }
    var index = 0
    for (bit in 0 until BITS) {
      val texel = cell[SQUARE_ROW * size.width + SQUARE_LEFT + bit * SQUARE_STEP] and RGB
      require(texel == RGB || texel == 0) { "Square $bit holds ${texel.toString(16)}, neither black nor white." }
      index = (index shl 1) or (if (texel == RGB) 1 else 0)
    }
    return index
  }

  private const val WHITE = 0xFFFFFFFF.toInt()
  private const val BLACK = 0xFF000000.toInt()
}
