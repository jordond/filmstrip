package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.geometry.Size
import java.io.InputStream

/**
 * One plane of a frame or a canvas, [width] by [height] texels of [texelBytes] bytes each, stored in rows from
 * [offset] in [data].
 *
 * [shiftX] and [shiftY] are how many times the plane halves the frame's width and height, which is how a subsampled
 * chroma plane is laid.
 */
internal class Plane(
  val width: Int,
  val height: Int,
  val texelBytes: Int,
  val shiftX: Int,
  val shiftY: Int,
  val data: ByteArray,
  val offset: Int = 0,
)

/**
 * A frame one run child wrote, [size] texels of the ffmpeg pixel format [format] laid as [planes].
 *
 * @property aspect The sample aspect ratio the frame carries, as ffmpeg's `setsar` spells it, or null when it carries
 * none.
 * @property range The range the frame is tagged with, as ffmpeg's `setparams` spells it, or null when it carries none.
 */
internal class RunFrame(
  val size: Size,
  val format: String,
  val planes: List<Plane>,
  val aspect: String? = null,
  val range: String? = null,
)

/**
 * The canvas a `grid` or `iovl` is put together on, [size] texels of the ffmpeg pixel format [format], every plane
 * held one after another in [data] the way ffmpeg's `rawvideo` reads them.
 */
internal class PictureCanvas private constructor(
  val size: Size,
  val format: String,
  val data: ByteArray,
  private val planes: List<Plane>,
) {
  /**
   * Copies the part of [frame] [width] by [height] texels from [left] and [top] onto this canvas at [x] and [y], plane
   * by plane, each rectangle shifted down by the plane's subsampling and cut to both planes' edges.
   */
  fun paint(
    frame: RunFrame,
    left: Int,
    top: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ) {
    for ((from, to) in frame.planes.zip(planes)) {
      val sourceX = left shr from.shiftX
      val sourceY = top shr from.shiftY
      val targetX = x shr to.shiftX
      val targetY = y shr to.shiftY
      val across = minOf(shiftedUp(width, from.shiftX), from.width - sourceX, to.width - targetX)
      val down = minOf(shiftedUp(height, from.shiftY), from.height - sourceY, to.height - targetY)
      if (across <= 0 || down <= 0) continue
      val bytes = across * to.texelBytes
      for (row in 0 until down) {
        val source = from.offset + ((sourceY + row) * from.width + sourceX) * from.texelBytes
        val target = to.offset + ((targetY + row) * to.width + targetX) * to.texelBytes
        System.arraycopy(from.data, source, data, target, bytes)
      }
    }
  }

  companion object {
    /**
     * A canvas of [size] texels of [format], laid out as [like] lays its planes, each plane painted with the texel
     * of [texels] at its index, or left at zero when [texels] is null.
     */
    fun of(
      size: Size,
      format: String,
      like: List<Plane>,
      texels: List<ByteArray>?,
    ): PictureCanvas {
      val sizes = like.map { Triple(shiftedUp(size.width, it.shiftX), shiftedUp(size.height, it.shiftY), it) }
      val data = ByteArray(sizes.sumOf { (width, height, plane) -> width * height * plane.texelBytes })
      var offset = 0
      val planes =
        sizes.mapIndexed { index, (width, height, plane) ->
          val laid = Plane(width, height, plane.texelBytes, plane.shiftX, plane.shiftY, data, offset)
          val texel = texels?.get(index)
          if (texel != null) {
            for (at in offset until offset + width * height * plane.texelBytes step texel.size) {
              texel.copyInto(data, at)
            }
          }
          offset += width * height * plane.texelBytes
          laid
        }
      return PictureCanvas(size, format, data, planes)
    }
  }
}

/**
 * The texels of a canvas the `rawvideo` format [format] names, one plane each, or null when it is not one a canvas is
 * laid as.
 *
 * Eight-bit packed RGB is one plane, 4:4:4 YCbCr three and grey one, every planar sample one byte at eight bits and two
 * little-endian bytes past that.
 */
internal fun canvasPlanes(format: String): List<Plane>? {
  PACKED_TEXEL_BYTES[format]?.let { return listOf(Plane(0, 0, it, 0, 0, ByteArray(0))) }
  val (layout, bitDepth) = y4mLayouts.entries.firstOrNull { it.value == format }?.key ?: return null
  return planesOf(layout, bitDepth, 0, 0)?.map { it.third }
}

/**
 * Reads every frame of the `yuv4mpegpipe` stream [stream] carries, handing each to [each] as it arrives, and gives
 * back how many there were, or null when the stream is not one or ends part way through a frame.
 *
 * Each frame's size and the bytes it holds go to [admits] before any of it is read, so a frame [admits] turns down is
 * never held. Reading stops at the first frame either answers false to and leaves the rest unread, so the caller
 * closes [stream] to stop whatever writes it.
 */
internal fun y4mFrames(
  stream: InputStream,
  admits: (Size, Long) -> Boolean,
  each: (RunFrame) -> Boolean,
): Int? {
  val header = stream.line()?.split(' ') ?: return null
  if (header.first() != Y4M_SIGNATURE) return null
  val width = header.firstNotNullOfOrNull { it.removePrefixOrNull("W")?.toIntOrNull() } ?: return null
  val height = header.firstNotNullOfOrNull { it.removePrefixOrNull("H")?.toIntOrNull() } ?: return null
  if (width <= 0 || height <= 0) return null
  val tag = header.firstNotNullOfOrNull { it.removePrefixOrNull("C") } ?: "420jpeg"
  val (layout, bitDepth) = y4mLayout(tag) ?: return null
  val format = y4mLayouts[layout to bitDepth] ?: return null
  val shapes = planesOf(layout, bitDepth, width, height) ?: return null
  val aspect =
    header
      .firstNotNullOfOrNull { it.removePrefixOrNull("A") }
      ?.takeIf { ratio -> ratio.split(':').all { (it.toIntOrNull() ?: 0) > 0 } }
      ?.replace(':', '/')
  val range = header.firstNotNullOfOrNull { it.removePrefixOrNull(Y4M_RANGE) }?.let(Y4M_RANGES::get)
  val frameBytes = shapes.sumOf { (across, down, shape) -> across.toLong() * down * shape.texelBytes }
  var frames = 0
  while (true) {
    val line = stream.line() ?: return frames
    if (!line.startsWith(Y4M_FRAME)) return null
    if (!admits(Size(width, height), frameBytes) || frameBytes > Int.MAX_VALUE) return frames
    val planes =
      shapes.map { (across, down, shape) ->
        val data = ByteArray(across * down * shape.texelBytes)
        if (stream.readNBytes(data, 0, data.size) != data.size) return null
        Plane(across, down, shape.texelBytes, shape.shiftX, shape.shiftY, data)
      }
    frames++
    if (!each(RunFrame(Size(width, height), format, planes, aspect, range))) return frames
  }
}

/**
 * Reads every frame of the `pam` images [stream] carries one after another, handing each to [each] as it arrives,
 * and gives back how many there were, or null when one is not an eight-bit RGB or RGB with alpha image or ends part
 * way.
 *
 * Each frame goes to [admits] before its texels are read, the way [y4mFrames] hands it, and reading stops at the
 * first frame either answers false to, leaving the rest unread.
 */
internal fun pamFrames(
  stream: InputStream,
  admits: (Size, Long) -> Boolean,
  each: (RunFrame) -> Boolean,
): Int? {
  var frames = 0
  while (true) {
    val signature = stream.line() ?: return frames
    if (signature != PAM_SIGNATURE) return null
    val fields = mutableMapOf<String, String>()
    while (true) {
      val line = stream.line() ?: return null
      if (line == PAM_END) break
      if (fields.size == MAX_PAM_FIELDS) return null
      fields[line.substringBefore(' ')] = line.substringAfter(' ', "")
    }
    val width = fields["WIDTH"]?.toIntOrNull() ?: return null
    val height = fields["HEIGHT"]?.toIntOrNull() ?: return null
    val format = PAM_FORMATS[fields["DEPTH"] to fields["MAXVAL"]] ?: return null
    if (width <= 0 || height <= 0) return null
    val texelBytes = PACKED_TEXEL_BYTES.getValue(format)
    val bytes = width.toLong() * height * texelBytes
    if (!admits(Size(width, height), bytes) || bytes > Int.MAX_VALUE) return frames
    val data = ByteArray(bytes.toInt())
    if (stream.readNBytes(data, 0, data.size) != data.size) return null
    frames++
    if (!each(RunFrame(Size(width, height), format, listOf(Plane(width, height, texelBytes, 0, 0, data))))) {
      return frames
    }
  }
}

// The layout and depth a y4m C tag names, 420jpeg read as 4:2:0 at eight bits and 444p10 as 4:4:4 at ten, or null
// for a layout no canvas is laid as.
private fun y4mLayout(tag: String): Pair<String, Int>? {
  val match = Y4M_LAYOUT.matchEntire(tag) ?: return null
  val (layout, deep, mono) = match.destructured
  val bitDepth = (deep.ifEmpty { mono }).ifEmpty { null }?.toInt() ?: EIGHT_BITS
  return (if (layout.startsWith(MONO)) MONO else layout) to bitDepth
}

// The width, height and shape of each plane of a [layout] frame of [width] by [height] samples of [bitDepth] bits.
private fun planesOf(
  layout: String,
  bitDepth: Int,
  width: Int,
  height: Int,
): List<Triple<Int, Int, Plane>>? {
  val sample = if (bitDepth == EIGHT_BITS) 1 else 2
  val (shiftX, shiftY) = CHROMA_SHIFTS[layout] ?: return null
  val luma = Triple(width, height, Plane(0, 0, sample, 0, 0, ByteArray(0)))
  if (layout == MONO) return listOf(luma)
  val chroma =
    Triple(shiftedUp(width, shiftX), shiftedUp(height, shiftY), Plane(0, 0, sample, shiftX, shiftY, ByteArray(0)))
  return listOf(luma, chroma, chroma)
}

// [length] halved [shift] times, rounded up, the way a subsampled plane covers an odd edge.
private fun shiftedUp(
  length: Int,
  shift: Int,
): Int = (length + (1 shl shift) - 1) shr shift

// One header line up to its newline, or null when the stream ends first or the line runs past any header.
internal fun InputStream.line(): String? {
  val line = StringBuilder()
  while (line.length < MAX_HEADER) {
    val next = read()
    if (next < 0) return null
    if (next == '\n'.code) return line.toString()
    line.append(next.toChar())
  }
  return null
}

internal fun String.removePrefixOrNull(prefix: String): String? =
  if (startsWith(prefix)) substring(prefix.length) else null

private const val MONO = "mono"
private val Y4M_LAYOUT = Regex("""(420|422|444|mono)(?:jpeg|mpeg2|paldv)?(?:p(\d+))?(\d*)""")

private val CHROMA_SHIFTS = mapOf("420" to (1 to 1), "422" to (1 to 0), "444" to (0 to 0), MONO to (0 to 0))

// The ffmpeg pixel format of each y4m layout and depth a run child writes.
private val y4mLayouts: Map<Pair<String, Int>, String> =
  buildMap {
    for (layout in listOf("420", "422", "444")) {
      put(layout to EIGHT_BITS, "yuv${layout}p")
      for (bitDepth in listOf(9, 10, 12, 14, 16)) put(layout to bitDepth, "yuv${layout}p${bitDepth}le")
    }
    put(MONO to EIGHT_BITS, "gray")
    for (bitDepth in listOf(9, 10, 12, 14, 16)) put(MONO to bitDepth, "gray${bitDepth}le")
  }

private val PACKED_TEXEL_BYTES = mapOf("rgb24" to 3, "rgba" to 4)
private val PAM_FORMATS = mapOf(("3" to "255") to "rgb24", ("4" to "255") to "rgba")

private const val Y4M_SIGNATURE = "YUV4MPEG2"
private const val Y4M_FRAME = "FRAME"
private const val Y4M_RANGE = "XCOLORRANGE="
private val Y4M_RANGES = mapOf("FULL" to "pc", "LIMITED" to "tv")
private const val PAM_SIGNATURE = "P7"
private const val PAM_END = "ENDHDR"

/**
 * The longest a header line of a run child's frames may be, its newline not counted, plus one.
 */
internal const val MAX_HEADER = 256

/**
 * The most fields one `pam` header may hold, more than ffmpeg's encoder writes, which is WIDTH, HEIGHT, DEPTH, MAXVAL
 * and TUPLTYPE.
 */
internal const val MAX_PAM_FIELDS = 8
