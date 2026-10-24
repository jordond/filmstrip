package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.decodedPng
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.HeifAlpha
import dev.jordond.filmstrip.internal.heifAlpha
import dev.jordond.filmstrip.internal.heifPrimaryIsTiled
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.filePathOf
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal actual fun decodingOverlayPictures(
  resolvers: List<EffectResolver>,
  toolchain: Toolchain,
): List<EffectResolver> {
  val decoder = FfmpegPictureDecoder(toolchain.ffmpeg)
  return resolvers.map { if (it is BuiltInEffectResolver) BuiltInEffectResolver(decoder) else it }
}

/**
 * Reads an overlay picture with the backend's own ffmpeg.
 *
 * The picture is opened the way an export opens it, bytes written to a file first and a URI read as the file it names,
 * so this reads exactly the pictures an export draws, turned by any orientation ffmpeg applies. Every read runs the
 * first frame of the picture's primary image through a filter. An AVIF or HEIC whose primary item is derived from
 * tiles, as [heifPrimaryIsTiled] reads it, goes through a complex filter graph whose one input is left unlabelled,
 * which ffmpeg binds to the assembled picture. Every other picture goes through a simple one, whose input is the
 * stream ffmpeg picks for its output, and for a HEIF file that is the primary item rather than a thumbnail written
 * ahead of it. A picture this process cannot read the bytes of takes the simple one too, so a tiled one ffmpeg alone
 * opens is refused rather than read off a thumbnail.
 *
 * A decode takes one of the routes [pictureRoute] picks for the picture's YCbCr coding. Most hand back the frame
 * through ffmpeg's own conversion to RGB, and it travels as a PNG of the depth and channels ffmpeg picks for the
 * source, so a grey picture stays grey and one deeper than eight bits keeps sixteen. The PNG carries the colour
 * description ffmpeg's decoder found in its `cICP` and `iCCP` chunks, read by [decodedPng]. A picture whose matrix
 * swscale cannot convert travels instead as the frame's 4:4:4 planes at its own depth in a `yuv4mpegpipe` stream, and
 * core's evaluator for its reading turns them into RGB here, with no description. A measure runs the frame through a
 * graph that hands it on untouched into ffmpeg's `framecrc` muxer, which decodes the frame and writes its size without
 * encoding a picture, so it is the size the decode hands back at a fraction of its cost.
 *
 * ffmpeg reads an AVIF or HEIC's alpha item, when it reads one at all, as a stream of its own beside the colour one.
 * So a picture whose file carries an alpha item, as [heifAlpha] reads it, runs a second child that decodes that
 * stream through [alphaFrame] as a sixteen-bit grey PNG, and its codes become the alpha of every texel either route
 * hands back. A file that records its colour as premultiplied by the alpha comes back straight. A build that lists
 * no stream for the item, as ffmpeg 6.1 does not, hands the picture back opaque with [UNLISTED_ALPHA] among its
 * [DecodedPicture.limits]. An alpha plane of another size than the picture is refused.
 *
 * An AVIF or HEIC is decoded from [heifWithExplicitCodestreamColour]'s bytes, whose codestream states the matrix and
 * range its `nclx` box records. Without an `nclx` it states its own signal, with a matrix it leaves out or leaves
 * unspecified written as BT.601, so the picture lands per its codestream. Its matrix and range are still set on the
 * conversion from what [pictureYcbcrCoding] reads, so the file's own box wins the way libavif and libheif read it. A
 * picture whose [YcbcrCoding.colourKnown] is false is left to the decoder.
 *
 * A picture with a [YcbcrCoding.refusal] is [OverlayPictureDecode.Undrawn] with core's reason before anything is
 * written or run. So is one whose matrix swscale cannot convert when core does not know the depth its samples are
 * stored at, with ffmpeg's own reason.
 *
 * Every read is a child process that never outlives the call. A decode's colour and alpha children share one
 * deadline, [timeoutMillis] after the decode starts, and a measure has one of its own. A child still running at its
 * deadline is stopped and the read refused. One whose calling thread is interrupted, which is how a cancelled plan
 * reaches it, is stopped and the [InterruptedException] is thrown on to the caller.
 *
 * @param scratch The directory picture bytes are written to, or null for the system's temporary directory.
 * @param write Writes picture bytes to the file ffmpeg reads them from.
 */
internal class FfmpegPictureDecoder(
  private val ffmpeg: String,
  private val timeoutMillis: Long = PICTURE_TIMEOUT_MILLIS,
  private val scratch: File? = null,
  private val write: (File, ByteArray) -> Unit = File::writeBytes,
) : OverlayPictureDecoder {
  // ffmpeg's swscale limit, not one this applies: a YCbCr source deeper than eight bits that swscale converts reaches
  // sixteen-bit RGB short of full scale, limited range at 255/256 and full range at 1023/1024, so its
  // StoredSamples.Rgba16 sit up to four ten-bit codes low. No flag or format hop moves it, and the shortfall is not one
  // scale, so nothing rescales it. A picture decoded from its planes has no shortfall.
  override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> {
    val deadline = deadlineFromNow()
    val bytes = encodedBytes(image)
    val opened = ::heifWithExplicitCodestreamColour
    val alpha = bytes?.let(::heifAlpha)
    return when (val route = pictureRoute(bytes?.let(::pictureYcbcrCoding))) {
      is PictureRoute.Refused -> {
        OverlayPictureDecode.Undrawn(route.reason)
      }
      is PictureRoute.Converted -> {
        readThrough(image, bytes, opened) { input ->
          val command = firstFrame(input, bytes, route.filter, PNG_ON_STDOUT)
          when (val colour = ranToEnd(command, deadline, ::wholeStream, ::decodedPng)) {
            is OverlayPictureRead.Refused -> {
              colour
            }
            is OverlayPictureRead.Read -> {
              withAlpha(input, alpha, deadline, colour.value, colour.value.size) { picture, plane ->
                plane?.appliedTo(picture) ?: picture
              }
            }
          }
        }
      }
      is PictureRoute.Planes -> {
        readThrough(image, bytes, opened) { input ->
          val command = firstFrame(input, bytes, route.filter, PLANES_ON_STDOUT)
          when (val colour = ranToEnd(command, deadline, { yuv4mpegPlanes(it, route.bitDepth) }) { it }) {
            is OverlayPictureRead.Refused -> {
              colour
            }
            is OverlayPictureRead.Read -> {
              withAlpha(input, alpha, deadline, colour.value, colour.value.size) { planes, plane ->
                decodedPlanes(planes, route.evaluator, plane)
              }
            }
          }
        }
      }
    }
  }

  // [colour] drawn with the plane of [alpha] read out of [input] by [deadline], or as it is when the file carries no
  // alpha item. A build that lists no stream for the item draws it opaque and names its limit, and a plane of another
  // [size] than the colour's is refused.
  @Throws(InterruptedException::class)
  private fun <C> withAlpha(
    input: String,
    alpha: HeifAlpha?,
    deadline: Long,
    colour: C,
    size: Size,
    draw: (C, PictureAlpha?) -> DecodedPicture,
  ): OverlayPictureRead<DecodedPicture> {
    if (alpha == null) return OverlayPictureRead.Read(draw(colour, null))
    val command = listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-i", input) + alphaFrame(alpha)
    return when (val plane = ranToEnd(command, deadline, ::wholeStream, ::decodedPng)) {
      is OverlayPictureRead.Refused -> {
        if (!unlistedAlpha(plane.reason)) return plane
        val opaque = draw(colour, null)
        val limited = DecodedPicture(opaque.size, opaque.samples, opaque.colour, opaque.limits + UNLISTED_ALPHA)
        OverlayPictureRead.Read(limited)
      }
      is OverlayPictureRead.Read -> {
        val read = pictureAlpha(plane.value, alpha)
        if (read.size != size) return OverlayPictureRead.Refused(alphaSizeReason(read.size, size))
        OverlayPictureRead.Read(draw(colour, read))
      }
    }
  }

  override fun measure(image: ImageSource): OverlayPictureRead<Size> {
    val bytes = encodedBytes(image)
    return readThrough(image, bytes, { it }) { input ->
      val command = firstFrame(input, bytes, PASS_THROUGH, SIZE_ON_STDOUT)
      ranToEnd(command, deadlineFromNow(), ::wholeStream, ::sizeOfFrameCrc)
    }
  }

  // The first frame of [input]'s primary image through [filter], written as [output]. A tiled primary item is only
  // reachable as a complex graph's unlabelled input, and a simple graph is the one that passes over a thumbnail.
  private fun firstFrame(
    input: String,
    bytes: ByteArray?,
    filter: String,
    output: List<String>,
  ): List<String> {
    val graph = if (bytes != null && heifPrimaryIsTiled(bytes)) "-filter_complex" else "-vf"
    return listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-i", input) +
      listOf("-frames:v", "1", graph, filter) + output
  }

  // Runs [read] on the file ffmpeg opens for [image], whose encoded [bytes] are null when this process cannot read
  // them. [opened] gives the bytes ffmpeg reads in their place. Bytes handed over as bytes, and a file whose bytes
  // [opened] changes, are written to a scratch file that lives for the length of [read].
  private fun <T> readThrough(
    image: ImageSource,
    bytes: ByteArray?,
    opened: (ByteArray) -> ByteArray,
    read: (String) -> OverlayPictureRead<T>,
  ): OverlayPictureRead<T> {
    val named = pathOf(image)
    val scratch = bytes?.let(opened)?.takeIf { named == null || it !== bytes }
    val written =
      scratch?.let {
        when (val file = writtenToFile(it)) {
          is OverlayPictureRead.Read -> file.value
          is OverlayPictureRead.Refused -> return file
        }
      }
    val input = written?.absolutePath ?: checkNotNull(named)

    return try {
      read(input)
    } finally {
      written?.delete()
    }
  }

  // The extension an export's scratch directory writes picture bytes under, so ffmpeg probes the same file name here.
  // A write that fails part way takes the file with it.
  private fun writtenToFile(bytes: ByteArray): OverlayPictureRead<File> {
    val file =
      try {
        File.createTempFile(SCRATCH_PREFIX, ".png", scratch)
      } catch (unwritable: IOException) {
        return OverlayPictureRead.Refused(unwritable.message.orEmpty())
      }

    var whole = false
    try {
      write(file, bytes)
      whole = true
    } catch (unwritable: IOException) {
      return OverlayPictureRead.Refused(unwritable.message.orEmpty())
    } finally {
      if (!whole) file.delete()
    }
    return OverlayPictureRead.Read(file)
  }

  // The System.nanoTime a read starting now has to finish by, [timeoutMillis] away.
  private fun deadlineFromNow(): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

  // Runs [command] to its end and hands back what [parse] makes of what [collect] reads out of its stdout, or why not
  // when it failed, wrote nothing they read or ran past [deadline], a System.nanoTime. Both pipes drain on threads of
  // their own, since a full one blocks the child, so this thread only ever waits, and a wait is what an interrupt
  // breaks. [collect] runs on the stdout thread and must read to the end. [parse] runs on this thread once the child
  // has exited. An interrupt stops the child and is thrown on, so a cancelled plan sees its cancellation rather than a
  // picture it could not read.
  @Throws(InterruptedException::class)
  private fun <R : Any, T> ranToEnd(
    command: List<String>,
    deadline: Long,
    collect: (InputStream) -> R?,
    parse: (R) -> T?,
  ): OverlayPictureRead<T> {
    val name = File(command.first()).name
    val process =
      try {
        ProcessBuilder(command).start()
      } catch (unstarted: IOException) {
        return OverlayPictureRead.Refused(unstarted.message.orEmpty())
      }

    val stdout = AtomicReference<R?>()
    val stderr = StderrTail()
    val drains =
      listOf(
        drained(process, "stdout") { stdout.set(collect(process.inputStream)) },
        drained(process, "stderr") {
          process.errorReader().forEachLine { line -> synchronized(stderr) { stderr.accept(line) } }
        },
      )

    try {
      runCatching { process.outputStream.close() }
      if (!process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
        return OverlayPictureRead.Refused("$name was still reading the picture after $timeoutMillis ms.")
      }
      drains.forEach { it.join(FRAME_EXIT_GRACE_MILLIS) }
      val read = stdout.get()?.takeIf { process.exitValue() == 0 }?.let(parse)
      return read?.let { OverlayPictureRead.Read(it) }
        ?: OverlayPictureRead.Refused(
          synchronized(stderr) { stderr.text() }.ifBlank { "$name read no picture out of it." },
        )
    } finally {
      settle(process, drains)
    }
  }
}

private fun drained(
  process: Process,
  stream: String,
  drain: () -> Unit,
): Thread =
  Thread { runCatching(drain) }.apply {
    name = "filmstrip-ffmpeg-picture-$stream-${process.pid()}"
    isDaemon = true
    start()
  }

// Stops [process] if it is still running, on the preview's ladder rather than the export's, and gives each drain a
// grace period to reach the end of its pipe. A picture read writes no file, so there is nothing for `q` to let it
// finish, and ffmpeg runs here with stdin closed anyway. An interrupt that led here, or lands during a wait, is held
// back across the waits, which would otherwise throw at once and skip every grace period, and put back afterwards.
private fun settle(
  process: Process,
  drains: List<Thread>,
) {
  var interrupted = Thread.interrupted()

  fun waited(wait: () -> Boolean): Boolean =
    try {
      wait()
    } catch (
      @Suppress("SwallowedException") again: InterruptedException,
    ) {
      interrupted = true
      false
    }

  try {
    if (process.isAlive) {
      process.destroy()
      if (!waited { process.waitFor(FRAME_STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS) }) {
        process.destroyForcibly()
        waited { process.waitFor(FRAME_STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
      }
    }
    // Best effort on purpose: a drain gets one join, and an interrupt during it ends that drain's wait early. The
    // drains are daemon threads that stop once the pipe closes, so one left running cannot hold the process open.
    drains.forEach { drain ->
      waited {
        drain.join(FRAME_STOP_GRACE_MILLIS)
        !drain.isAlive
      }
    }
  } finally {
    if (interrupted) Thread.currentThread().interrupt()
  }
}

// The file path [image] names, a URI read as the file it names, or null for bytes handed over as bytes.
private fun pathOf(image: ImageSource): String? =
  when (image) {
    is ImageSource.Path -> image.path
    is ImageSource.Uri -> filePathOf(image.uri) ?: image.uri
    is ImageSource.Bytes -> null
  }

// The encoded bytes of [image], or null when this process cannot read them.
private fun encodedBytes(image: ImageSource): ByteArray? =
  if (image is ImageSource.Bytes) image.bytes else pathOf(image)?.let { readable(File(it)) }

// Everything left in [stream], or null when that is nothing.
private fun wholeStream(stream: InputStream): ByteArray? = stream.readAllBytes().takeIf { it.isNotEmpty() }

// The bytes of [file], or null when it is not a file this process can read.
private fun readable(file: File): ByteArray? =
  try {
    file.takeIf { it.isFile }?.readBytes()
  } catch (unreadable: IOException) {
    null
  }

// The width and height the `#dimensions` line of ffmpeg's framecrc output gives its first stream, or null when it has
// none.
internal fun sizeOfFrameCrc(output: ByteArray): Size? {
  val dimensions =
    output
      .decodeToString()
      .lineSequence()
      .firstOrNull { it.startsWith(FRAMECRC_DIMENSIONS) }
      ?.removePrefix(FRAMECRC_DIMENSIONS)
      ?.trim() ?: return null
  val width = dimensions.substringBefore('x').toIntOrNull()
  val height = dimensions.substringAfter('x', "").toIntOrNull()
  return if (width != null && height != null && width > 0 && height > 0) Size(width, height) else null
}

// A graph that hands its frame on untouched.
internal const val PASS_THROUGH = "null"

private val PNG_ON_STDOUT = listOf("-c:v", "png", "-f", "image2pipe", "-")

// The frame's planes as they are behind a short text header. The muxer takes 4:4:4 past eight bits only when told to
// be less strict.
private val PLANES_ON_STDOUT = listOf("-f", "yuv4mpegpipe", "-strict", "-1", "-")

// The frame wrapped rather than encoded, so the muxer writes its size and a checksum and no picture.
private val SIZE_ON_STDOUT = listOf("-c:v", "wrapped_avframe", "-f", "framecrc", "-")

private const val FRAMECRC_DIMENSIONS = "#dimensions 0:"

// Long enough for a large AVIF on a loaded machine, and short of leaving a plan hung on a child that never finishes.
internal const val PICTURE_TIMEOUT_MILLIS: Long = 30_000

private const val SCRATCH_PREFIX = "filmstrip-overlay"
