package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.HeifGrid
import dev.jordond.filmstrip.picture.heif.HeifOverlayCut
import dev.jordond.filmstrip.picture.heif.assemblyFrameBudget
import dev.jordond.filmstrip.picture.heif.heifCodedImages
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * How a `grid` or `iovl` is put together from its coded images on a canvas of [size].
 *
 * Each image in [layout] is decoded through [filter] and written as [frames]. The canvas is laid in [format], or in
 * the first frame's format when it is null, and painted with the texels [texels] gives for that format before any
 * frame lands. The finished canvas goes through [post]. When [reference] is set, the first image is decoded once more
 * through it and the canvas drawn over it, so the canvas carries that frame's color tags, side data and aspect ratio.
 *
 * @property reference The filter the first image runs through as the reference, or null for none.
 */
internal class Assembly(
  val size: Size,
  val layout: AssemblyLayout,
  val filter: String,
  val frames: FrameKind,
  val format: String?,
  val texels: (String) -> List<ByteArray>?,
  val reference: String?,
  val post: String,
)

/**
 * The format a run child writes its frames in, the arguments that write it and how they are read back.
 */
internal enum class FrameKind(
  val arguments: List<String>,
  val read: (InputStream, (Size, Long) -> Boolean, (RunFrame) -> Boolean) -> Int?,
) {
  // The frames' own planes behind one header. The muxer takes 4:4:4 past eight bits only when told to be less strict.
  Y4m(listOf("-f", "yuv4mpegpipe", "-strict", "-1", "-"), ::y4mFrames),

  // One packed RGB image a frame.
  Pam(listOf("-c:v", "pam", "-f", "image2pipe", "-"), ::pamFrames),
}

/**
 * Which coded images a picture is put together from and where each lands.
 */
internal sealed interface AssemblyLayout {
  /**
   * The ids of the coded images, in the order they are drawn.
   */
  val items: List<Long>

  /**
   * Where the frame at [index] of [items], decoded at [frame], lands when the first decoded at [first], or null when
   * it does not fit where the picture places it.
   */
  fun placement(
    index: Int,
    frame: Size,
    first: Size,
  ): Placement?

  /**
   * A `grid`'s tiles, each drawn whole in rows by [HeifGrid.columns], spaced by the first tile's size.
   *
   * Every tile must decode at the first tile's size, and the tiles together must cover [HeifGrid.size].
   */
  class Tiles(
    private val grid: HeifGrid,
  ) : AssemblyLayout {
    override val items: List<Long> = grid.tiles

    override fun placement(
      index: Int,
      frame: Size,
      first: Size,
    ): Placement? {
      val covers =
        grid.columns.toLong() * first.width >= grid.size.width && grid.rows.toLong() * first.height >= grid.size.height
      if (frame != first || !covers) return null
      val x = index % grid.columns * first.width
      val y = index / grid.columns * first.height
      return Placement(0, 0, x, y, frame)
    }
  }

  /**
   * The parts of an `iovl`'s inputs that land on its canvas, as [cuts] gives them, in order.
   *
   * With no cut, [unshown] is decoded alone when it is set, so the canvas takes its format, and nothing is drawn.
   */
  class Cuts(
    private val cuts: List<HeifOverlayCut>,
    unshown: Long?,
  ) : AssemblyLayout {
    override val items: List<Long> = cuts.map { it.input.item }.ifEmpty { listOfNotNull(unshown) }

    override fun placement(
      index: Int,
      frame: Size,
      first: Size,
    ): Placement? {
      val cut = cuts.getOrNull(index) ?: return Placement.NONE
      val holds =
        cut.left.toLong() + cut.size.width <= frame.width && cut.top.toLong() + cut.size.height <= frame.height
      return if (holds) Placement(cut.left, cut.top, cut.x, cut.y, cut.size) else null
    }
  }
}

/**
 * The part of a frame [size] texels from [left] and [top] that lands on the canvas at [x] and [y].
 */
internal class Placement(
  val left: Int,
  val top: Int,
  val x: Int,
  val y: Int,
  val size: Size,
) {
  companion object {
    // A frame that is decoded and drawn nowhere.
    val NONE = Placement(0, 0, 0, 0, Size(0, 0))
  }
}

/**
 * Puts a picture together the way [assembly] lays it out from the coded images of the AVIF or HEIC [bytes], and hands
 * back what [parse] makes of what [collect] reads from the child that runs the canvas through its post filter.
 *
 * The images are read by [heifCodedImages] and split into runs by [pictureRuns]. Each run is one child that reads the
 * run as one elementary stream on its standard input and decodes it on one thread, and its frames are painted onto the
 * canvas as they arrive, so no child ever holds more than one stream and the canvas is the only frame this process
 * holds whole. A run after the canvas takes its format is converted to that format. Images that cannot be read out of
 * the file are refused with [CODED_IMAGES_REASON], and a frame that does not fit where the picture places it with
 * [codedImageSizeReason]. So is a frame that would hold more bytes than [assemblyFrameBudget] leaves beside the
 * canvas, and it is turned down before any of it is read. A frame turned down stops its run child there rather than
 * leaving it to decode the rest.
 *
 * The canvas then goes to one more child as `rawvideo` on its standard input, beside the first image as the reference
 * when [Assembly.reference] is set, written to a file under [scratch] that lives for the length of that child. Every
 * child shares [deadline].
 */
@Throws(InterruptedException::class)
internal fun <R : Any, T> assembled(
  ffmpeg: String,
  assembly: Assembly,
  bytes: ByteArray,
  deadline: Long,
  timeoutMillis: Long,
  scratch: File?,
  output: List<String>,
  collect: (InputStream) -> R?,
  parse: (R) -> T?,
): OverlayPictureRead<T> {
  val items = assembly.layout.items
  val images =
    if (items.isEmpty()) {
      emptyList()
    } else {
      heifCodedImages(bytes, items)
        ?: return OverlayPictureRead.Refused(CODED_IMAGES_REASON)
    }
  val runs = pictureRuns(images) ?: return OverlayPictureRead.Refused(CODED_IMAGES_REASON)
  val painting = Painting(assembly)
  for (run in runs) {
    val format = painting.canvas?.format
    val filter = if (format == null) assembly.filter else "${assembly.filter},format=$format"
    val command =
      decoding(ffmpeg) + listOf("-f", run.format, "-i", "-", "-fps_mode", "passthrough", "-vf", filter) +
        assembly.frames.arguments
    val read = ranToEnd(command, deadline, timeoutMillis, run::writeTo, { painting.ran(it, run.images) }, { it })
    painting.turnedDown?.let { return OverlayPictureRead.Refused(it) }
    when (read) {
      is OverlayPictureRead.Refused -> return read
      is OverlayPictureRead.Expired -> return read
      is OverlayPictureRead.Read -> read.value.refusal?.let { return OverlayPictureRead.Refused(it) }
    }
  }
  val canvas = painting.canvas ?: return OverlayPictureRead.Refused(CODED_IMAGES_REASON)
  val raw = listOf("-f", "rawvideo", "-pix_fmt", canvas.format, "-s", "${canvas.size.width}x${canvas.size.height}")
  val post = (painting.tags + assembly.post).joinToString(",")
  val canvasBytes = { out: OutputStream -> out.write(canvas.data) }
  val reference =
    assembly.reference ?: return ranToEnd(
      decoding(ffmpeg) + raw + listOf("-i", "-", "-frames:v", "1", "-vf", post) + output,
      deadline,
      timeoutMillis,
      canvasBytes,
      collect,
      parse,
    )
  val first = pictureRun(images.take(1)) ?: return OverlayPictureRead.Refused(CODED_IMAGES_REASON)
  val file =
    try {
      scratchFile(scratch, ".${first.format}", first::writeTo)
    } catch (unwritable: IOException) {
      return OverlayPictureRead.Refused(unwritable.message.orEmpty())
    }
  val graph =
    "[1:v]$reference[ref];[ref][0:v]xstack=inputs=2:layout=0_0|0_0," +
      "crop=${canvas.size.width}:${canvas.size.height}:0:0,${assembly.post}"
  return try {
    val inputs = raw + listOf("-i", "-") + INPUT_OPTIONS + listOf("-f", first.format, "-i", file.path)
    val graphed = listOf("-frames:v", "1", "-filter_complex_threads", "1", "-filter_complex", graph)
    ranToEnd(decoding(ffmpeg) + inputs + graphed + output, deadline, timeoutMillis, canvasBytes, collect, parse)
  } finally {
    file.delete()
  }
}

/**
 * A new file under [scratch], or the default temporary directory when it is null, holding what [write] writes to it.
 * The file is deleted again when [write] throws, and the throw goes on.
 */
internal fun scratchFile(
  scratch: File?,
  suffix: String,
  write: (OutputStream) -> Unit,
): File {
  val file = File.createTempFile(SCRATCH_PREFIX, suffix, scratch)
  var written = false
  try {
    file.outputStream().buffered().use(write)
    written = true
  } finally {
    if (!written) file.delete()
  }
  return file
}

/**
 * The first frame of the coded image [item] of the AVIF or HEIC [bytes] through [filter], written as [output], read
 * the way [assembled] reads it and decoded alone as one elementary stream on one thread.
 *
 * An image that cannot be read out of the file is refused with [CODED_IMAGES_REASON].
 */
@Throws(InterruptedException::class)
internal fun <R : Any, T> decodedAlone(
  ffmpeg: String,
  item: Long,
  bytes: ByteArray,
  filter: String,
  deadline: Long,
  timeoutMillis: Long,
  output: List<String>,
  collect: (InputStream) -> R?,
  parse: (R) -> T?,
): OverlayPictureRead<T> {
  val run =
    heifCodedImages(bytes, listOf(item))?.let(::pictureRun) ?: return OverlayPictureRead.Refused(CODED_IMAGES_REASON)
  val command = decoding(ffmpeg) + listOf("-f", run.format, "-i", "-", "-frames:v", "1", "-vf", filter) + output
  return ranToEnd(command, deadline, timeoutMillis, run::writeTo, collect, parse)
}

/**
 * The reason a picture whose coded images cannot be read out of its file, or framed as streams ffmpeg reads, is not
 * drawn.
 */
internal const val CODED_IMAGES_REASON =
  "The overlay image's coded images cannot be read out of the file, so this overlay picture cannot be drawn."

/**
 * Why a picture whose coded image [item] decodes at [size], which does not fit where the picture places it, is not
 * drawn.
 */
internal fun codedImageSizeReason(
  item: Long,
  size: Size,
): String =
  "The overlay image's coded image $item decodes at ${size.width}x${size.height}, which does not fit where the " +
    "picture places it, so this overlay picture cannot be drawn."

/**
 * Why a picture whose coded image [item] decodes as [format] on a canvas of [canvas] is not drawn.
 */
internal fun codedImageFormatReason(
  item: Long,
  format: String,
  canvas: String,
): String =
  "The overlay image's coded image $item decodes as $format on a canvas of $canvas, so this overlay picture cannot " +
    "be drawn."

// ffmpeg ahead of its first input, quiet, turning nothing by any orientation it reads and decoding on one thread.
private fun decoding(ffmpeg: String): List<String> =
  listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error") + INPUT_OPTIONS

private val INPUT_OPTIONS = listOf("-noautorotate", "-threads", "1")

// How one run went, painted whole when [refusal] is null.
private class RunRead(
  val refusal: String?,
)

// The canvas and how far painting it has come, across every run. Each run's frames are painted on the thread that
// reads them, which hands this back through its stdout only once the run is read to its end.
private class Painting(
  private val assembly: Assembly,
) {
  var canvas: PictureCanvas? =
    assembly.format?.let { format ->
      canvasPlanes(format)?.let { PictureCanvas.of(assembly.size, format, it, assembly.texels(format)) }
    }
  private var painted = 0
  private var first: Size? = null

  // The filters that tag the canvas with the sample aspect ratio and range the first frame carries, since a raw
  // canvas carries neither.
  var tags: List<String> = emptyList()

  private val frameBudget = assemblyFrameBudget(assembly.size)

  // Why the last run stopped reading at a frame it turned down, or null when it turned none down. The reading stops
  // there, which closes the run child's output and so stops the child as well.
  @Volatile
  var turnedDown: String? = null

  // Paints each frame [stream] carries, every one of the run's [images] or why not. A frame past the run's images, or
  // one that would hold more than [frameBudget], is turned down before it is read.
  fun ran(
    stream: InputStream,
    images: Int,
  ): RunRead {
    val start = painted
    val admits = { size: Size, bytes: Long ->
      turnedDown =
        when {
          painted - start >= images -> CODED_IMAGES_REASON
          bytes > frameBudget -> codedImageSizeReason(assembly.layout.items[painted], size)
          else -> null
        }
      turnedDown == null
    }
    val read =
      assembly.frames.read(stream, admits) { frame ->
        turnedDown = paint(frame)
        turnedDown == null
      }
    return RunRead(turnedDown ?: CODED_IMAGES_REASON.takeIf { read == null || painted - start != images })
  }

  private fun paint(frame: RunFrame): String? {
    val item = assembly.layout.items[painted]
    val laid =
      canvas ?: PictureCanvas.of(assembly.size, frame.format, frame.planes, assembly.texels(frame.format)).also {
        canvas = it
      }
    if (frame.format != laid.format) return codedImageFormatReason(item, frame.format, laid.format)
    val start =
      first ?: frame.size.also {
        first = it
        tags = listOfNotNull(frame.aspect?.let { "setsar=$it" }, frame.range?.let { "setparams=range=$it" })
      }
    val placed = assembly.layout.placement(painted, frame.size, start) ?: return codedImageSizeReason(item, frame.size)
    laid.paint(frame, placed.left, placed.top, placed.x, placed.y, placed.size.width, placed.size.height)
    painted++
    return null
  }
}

private const val SCRATCH_PREFIX = "filmstrip-overlay"
