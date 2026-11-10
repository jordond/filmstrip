package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FilterGraphBuilder
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * A dense grid of BT.2020 signals laid one per pixel on a frame, for tests that check an HDR graph pixel by pixel.
 *
 * The grid holds every corner and edge of the signal cube, the edges at a step finer than a ten-bit code, and a grid of
 * [STEPS] steps a side through the rest of it. The frame is [WIDTH] pixels wide and [ROWS] high, and any pixel past the
 * grid repeats its last signal.
 */
internal object HdrGrid {
  const val STEPS = 64
  private const val EDGE_STEPS = 1024

  /**
   * The red, green and blue signal of each pixel on the grid, in the order the frame lays them out.
   *
   * The cube's grid comes first, then its twelve edges one after another.
   */
  val SIGNALS: List<FloatArray> =
    buildList {
      val steps = 0..STEPS
      steps.forEach { r -> steps.forEach { g -> steps.forEach { b -> add(signalOf(r, g, b, STEPS)) } } }
      // Each edge runs along one channel with the other two at a corner, one bit of the corner each.
      (0..2).forEach { axis ->
        (0..3).forEach { corner ->
          (1 until EDGE_STEPS).forEach { step ->
            val others = listOf(corner and 1, corner shr 1).map { it * EDGE_STEPS }.iterator()
            val at = IntArray(3) { if (it == axis) step else others.next() }
            add(signalOf(at[0], at[1], at[2], EDGE_STEPS))
          }
        }
      }
    }

  const val WIDTH = 1024
  val ROWS = (SIGNALS.size + WIDTH - 1) / WIDTH
  val PIXELS = WIDTH * ROWS

  /**
   * How many misses a failing grid test lists.
   *
   * The count of all of them comes first, so a failure on every pixel of the grid stays short enough to read.
   */
  const val MISSES_SHOWN = 20

  private fun signalOf(
    r: Int,
    g: Int,
    b: Int,
    steps: Int,
  ): FloatArray = floatArrayOf(r.toFloat() / steps, g.toFloat() / steps, b.toFloat() / steps)
}

/**
 * The pixel formats, frame and attributes the HDR graph tests render and resolve effects with.
 *
 * Effects resolve for ffmpeg's filter graph on a BT.2020 frame the size of the HDR fixtures', and graphs read and write
 * the formats named here.
 */
internal object HdrRender {
  /**
   * The grade's own ten-bit 4:2:0 Y'CbCr.
   *
   * What an HDR export writes and what a color effect on a kept grade reads.
   */
  const val GRADE_YUV = "yuv420p10le"

  /**
   * Sixteen-bit 4:4:4 Y'CbCr, which holds every plane at full size.
   *
   * A test reads a graph's codes in it before any chroma is resampled.
   */
  const val WIDE_YUV = "yuv444p16le"

  /**
   * Ten-bit 4:4:4 Y'CbCr, which a flat or grid frame of whole codes is drawn in.
   *
   * Each pixel keeps its own chroma, so no code is resampled before the graph reads it.
   */
  const val FRAME_YUV = "yuv444p10le"

  /**
   * Planar float RGB, which stores green, blue and red in that order.
   *
   * What the HDR graphs carry light and signal in.
   */
  const val FLOAT_RGB = "gbrpf32le"

  /**
   * [GRADE_YUV] with an alpha plane after its chroma.
   *
   * What a color effect or an overlay on a kept grade writes, so the fill still shows where the frame is clear.
   */
  const val GRADE_YUVA = "yuva420p10le"

  /**
   * [WIDE_YUV] with an alpha plane after its chroma.
   *
   * A test reads a graph that carries alpha in it before any chroma is resampled.
   */
  const val WIDE_YUVA = "yuva444p16le"

  /**
   * [FLOAT_RGB] with an alpha plane after red.
   *
   * What the HDR graphs carry light and signal in when the frame's alpha comes along.
   */
  const val FLOAT_RGBA = "gbrapf32le"

  /**
   * The side of a flat test frame, in pixels.
   *
   * It is even, so a 4:2:0 frame has whole chroma samples.
   */
  const val SIDE = 16

  /**
   * The frame rate effects resolve at.
   *
   * It matches the HDR fixtures'.
   */
  const val RATE = 30f

  /**
   * The HDR fixtures' frame, which a crop to portrait contains.
   *
   * Effects resolve for a frame this size.
   */
  val FIXTURE_SIZE = Size(1280, 720)

  /**
   * What ffmpeg's filter graph offers an effect on a kept HDR grade.
   *
   * No shaders, BT.2020 and a texture limit well past any frame here.
   */
  val CAPABILITIES =
    RenderCapabilities(
      api = RenderApi.FilterGraph,
      supportsFragmentShader = false,
      supportsComputeShader = false,
      supportsHdr = true,
      colorSpaces = setOf(ColorSpace.Bt2020),
      maxTextureSize = 16_384,
      features = emptySet(),
    )

  /**
   * The attributes of a BT.2020 frame the size of [FIXTURE_SIZE] on a [transfer] grade, at [RATE], for an effect that
   * spans the first [length] of it.
   */
  fun attributes(
    transfer: HdrTransfer,
    length: Duration,
  ): Attributes =
    Attributes(
      inputSize = FIXTURE_SIZE,
      outputSize = FIXTURE_SIZE,
      layoutSize = FIXTURE_SIZE,
      colorSpace = ColorSpace.Bt2020,
      hdrTransfer = transfer,
      frameRate = RATE,
      span = TimeRange.of(Duration.ZERO, length),
    )
}

/**
 * A raw frame the size of [HdrGrid]'s, read by ffmpeg as [format].
 *
 * If there is a [tag], it runs on the frame before the graph does, as a filter ahead of the graph would leave it.
 */
internal class RawFrame(
  val bytes: ByteArray,
  val format: String,
  val tag: FilterNode? = null,
)

/**
 * Runs this frame through [nodes] and returns the one frame that comes out, written raw as [format].
 *
 * The nodes go through the graph builder, as the backend writes them, and nothing converts the frame on the way out.
 */
internal fun RawFrame.rendered(
  nodes: List<FilterNode>,
  format: String,
): ByteArray = ffmpeg(nodes, format, loglevel = "error").first

/**
 * Runs this frame through [nodes] with ffmpeg logging at info and returns the log, for a test that reads what a filter
 * such as showinfo prints about the frame.
 *
 * The frame that comes out is written raw as [format] and dropped.
 */
internal fun RawFrame.logged(
  nodes: List<FilterNode>,
  format: String,
): String = ffmpeg(nodes, format, loglevel = "info").second

private fun RawFrame.ffmpeg(
  nodes: List<FilterNode>,
  format: String,
  loglevel: String,
): Pair<ByteArray, String> {
  val file = Files.createTempFile("grid", ".raw").toFile().apply { deleteOnExit() }
  file.writeBytes(bytes)
  val size = "${HdrGrid.WIDTH}x${HdrGrid.ROWS}"
  val input = listOf("-f", "rawvideo", "-pix_fmt", this.format, "-s", size, "-i", file.path)
  return try {
    ffmpeg(input, "[0:v]" + (tag?.render() ?: "null"), nodes, format, loglevel)
  } finally {
    file.delete()
  }
}

/**
 * Runs [source], a filter chain on ffmpeg's [input] or a source of its own, through [nodes] and returns the one frame
 * that comes out, written raw as [format].
 *
 * The nodes go through the graph builder, as the backend writes them.
 */
internal fun filtered(
  input: List<String>,
  source: String,
  nodes: List<FilterNode>,
  format: String,
): ByteArray = ffmpeg(input, source, nodes, format, loglevel = "error").first

// The one frame out of [source] through [nodes] and ffmpeg's log at [loglevel]. The log is read on a thread of its
// own, so a long one cannot fill the pipe and stall ffmpeg while the frame is still being read.
private fun ffmpeg(
  input: List<String>,
  source: String,
  nodes: List<FilterNode>,
  format: String,
  loglevel: String,
): Pair<ByteArray, String> {
  val graph = FilterGraphBuilder().apply { chain(listOf(SOURCE_PAD), nodes, OUTPUT_PAD) }.build(OUTPUT_PAD)
  val filter = "$source[$SOURCE_PAD];$graph"
  val command =
    listOf(hostFfmpeg, "-hide_banner", "-loglevel", loglevel) + input +
      listOf("-filter_complex", filter, "-map", "[$OUTPUT_PAD]", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", format)
  val process = ProcessBuilder(command + "-").redirectErrorStream(false).start()
  val log = StringBuilder()
  val drain = Thread { log.append(process.errorStream.readAllBytes().decodeToString()) }.apply { start() }
  val bytes = process.inputStream.readAllBytes()
  val exit = process.waitFor()
  drain.join()
  withClue("ffmpeg failed on $filter: $log") { exit shouldBe 0 }
  return bytes to log.toString()
}

/**
 * This fragment's [FilterFragment.chain] with every sidecar it reads written to a temporary file, and each placeholder
 * swapped for that file's path, the way the backend writes them before a graph runs.
 *
 * The files are deleted when the JVM exits.
 */
internal fun FilterFragment.writtenChain(): List<FilterNode> = written().chain

/**
 * This fragment with every sidecar it reads written to a temporary file, and each placeholder in its chain, its post
 * nodes and its branches' chains swapped for that file's path, the way the backend writes them before a graph runs.
 *
 * The files are deleted when the JVM exits.
 */
internal fun FilterFragment.written(): FilterFragment {
  val paths =
    sidecars.associate { sidecar ->
      val file = Files.createTempFile("sidecar", ".${sidecar.extension}").toFile().apply { deleteOnExit() }
      file.writeBytes(sidecar.bytes)
      sidecar.placeholder to file.path
    }

  fun List<FilterNode>.swapped(): List<FilterNode> =
    map { node -> FilterNode(node.name, node.arguments.map { FilterArgument(it.key, paths[it.value] ?: it.value) }) }

  return FilterFragment(
    chain = chain.swapped(),
    auxInputs = auxInputs.map { AuxInput(it.image, it.chain.swapped(), it.timeline) },
    merge = merge,
    sidecars = sidecars,
    crop = crop,
    post = post.swapped(),
  )
}

/**
 * This raw frame of [format] with its alpha plane cut off, so a reader of the same format without alpha reads it.
 *
 * [format] is one of [HdrRender.GRADE_YUVA], [HdrRender.WIDE_YUVA] or [HdrRender.FLOAT_RGBA], each of which stores its
 * alpha as its last plane.
 */
internal fun ByteArray.withoutAlpha(format: String): ByteArray =
  when (format) {
    // Luma and alpha are full size and each chroma plane a quarter of it.
    HdrRender.GRADE_YUVA -> copyOf(size / 5 * 3)
    HdrRender.WIDE_YUVA, HdrRender.FLOAT_RGBA -> copyOf(size / 4 * 3)
    else -> error("$format holds no alpha plane this reads")
  }

/**
 * Lays planes of float samples out as a planar frame of the grid's size.
 *
 * Each plane is padded with its last sample, so a plane shorter than the frame still fills it.
 */
internal fun planarFloats(planes: List<List<Float>>): ByteArray {
  val buffer = ByteBuffer.allocate(planes.size * HdrGrid.PIXELS * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
  planes.forEach { plane -> repeat(HdrGrid.PIXELS) { buffer.putFloat(plane[minOf(it, plane.lastIndex)]) } }
  return buffer.array()
}

/**
 * Lays planes of sixteen-bit samples out as a planar frame of the grid's size.
 *
 * Each plane is padded with its last sample, so a plane shorter than the frame still fills it.
 */
internal fun planarShorts(planes: List<IntArray>): ByteArray {
  val buffer = ByteBuffer.allocate(planes.size * HdrGrid.PIXELS * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
  planes.forEach { plane -> repeat(HdrGrid.PIXELS) { buffer.putShort(plane[minOf(it, plane.lastIndex)].toShort()) } }
  return buffer.array()
}

/**
 * Lays sixteen-bit samples out as a packed frame of the grid's size, one pixel after another with its channels in the
 * order [channels] lists them.
 */
internal fun packedShorts(channels: List<IntArray>): ByteArray {
  val buffer = ByteBuffer.allocate(channels.size * HdrGrid.PIXELS * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
  repeat(HdrGrid.PIXELS) { pixel -> channels.forEach { buffer.putShort(it[minOf(pixel, it.lastIndex)].toShort()) } }
  return buffer.array()
}

/**
 * Reads the first [count] samples of each of three planes of a sixteen-bit planar frame of the grid's size, one array
 * per plane.
 */
internal fun gridSamples(
  bytes: ByteArray,
  count: Int,
): List<IntArray> {
  val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
  withClue("a sixteen-bit planar grid frame") { samples.remaining() shouldBe HdrGrid.PIXELS * 3 }
  return (0..2).map { plane -> IntArray(count) { samples.get(plane * HdrGrid.PIXELS + it).toInt() and 0xFFFF } }
}

/**
 * Reads the red, green and blue of the first [count] pixels of a planar float frame of the grid's size, which stores
 * green, blue and red in that order, one array per pixel.
 */
internal fun gridLight(
  bytes: ByteArray,
  count: Int,
): List<FloatArray> {
  val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
  val plane = HdrGrid.PIXELS
  withClue("a planar float grid frame") { samples.remaining() shouldBe plane * 3 }
  return (0 until count).map { floatArrayOf(samples.get(2 * plane + it), samples.get(it), samples.get(plane + it)) }
}

/**
 * The mean luma, Cb and Cr of a flat ten-bit 4:2:0 frame [side] pixels square.
 *
 * Each plane's mean is rounded to the nearest code.
 */
internal fun flatCodes(
  bytes: ByteArray,
  side: Int,
): IntArray {
  val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
  val luma = side * side
  val chroma = luma / 4
  withClue("a ten-bit 4:2:0 frame of $side pixels square") { samples.remaining() shouldBe luma + chroma * 2 }

  fun mean(
    from: Int,
    count: Int,
  ): Int = ((from until from + count).sumOf { samples.get(it).toInt() and 0xFFFF } / count.toDouble()).roundToInt()

  return intArrayOf(mean(0, luma), mean(luma, chroma), mean(luma + chroma, chroma))
}

/**
 * The mean red, green and blue of a flat planar float frame [side] pixels square, which stores green, blue and red in
 * that order.
 */
internal fun flatLight(
  bytes: ByteArray,
  side: Int,
): FloatArray {
  val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
  val plane = side * side
  withClue("a planar float frame of $side pixels square") { samples.remaining() shouldBe plane * 3 }

  fun mean(index: Int): Float = (index * plane until (index + 1) * plane).map { samples.get(it) }.average().toFloat()

  return floatArrayOf(mean(2), mean(0), mean(1))
}

private const val SOURCE_PAD = "source"
private const val OUTPUT_PAD = "out"
