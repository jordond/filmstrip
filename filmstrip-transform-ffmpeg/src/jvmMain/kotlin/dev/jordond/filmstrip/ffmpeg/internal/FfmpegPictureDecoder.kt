package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.filePathOf
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal actual fun decodingOverlayPictures(
  resolvers: List<EffectResolver>,
  toolchain: Toolchain,
): List<EffectResolver> {
  val decoder = FfmpegPictureDecoder(toolchain.ffmpeg, toolchain.ffprobe)
  return resolvers.map { if (it is BuiltInEffectResolver) BuiltInEffectResolver(decoder) else it }
}

/**
 * Reads an overlay picture with the backend's own ffmpeg and ffprobe.
 *
 * The picture is opened the way an export opens it, bytes written to a file first and a URI read as the file it names,
 * so this reads exactly the pictures an SDR export draws. A decode hands back the first frame as `rgba` through
 * ffmpeg's own conversion, the one the SDR overlay's `format=rgba` runs, and it travels as PAM, which carries the size
 * and the samples and no colour metadata for anything to act on. A measure asks ffprobe for the size alone.
 *
 * Every read is a child process that never outlives the call. One still running after [timeoutMillis] is stopped and
 * refused. One whose calling thread is interrupted, which is how a cancelled plan reaches it, is stopped and the
 * [InterruptedException] is thrown on to the caller.
 *
 * @param scratch The directory picture bytes are written to, or null for the system's temporary directory.
 * @param write Writes picture bytes to the file ffmpeg reads them from.
 */
internal class FfmpegPictureDecoder(
  private val ffmpeg: String,
  private val ffprobe: String,
  private val timeoutMillis: Long = PICTURE_TIMEOUT_MILLIS,
  private val scratch: File? = null,
  private val write: (File, ByteArray) -> Unit = File::writeBytes,
) : OverlayPictureDecoder {
  override fun decode(image: ImageSource): OverlayPictureRead<BufferedImage> =
    readThrough(image) { input ->
      val command =
        listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-i", input) +
          listOf("-frames:v", "1", "-pix_fmt", "rgba", "-c:v", "pam", "-f", "image2pipe", "-")
      ranToEnd(command, ::pictureOfPam)
    }

  override fun measure(image: ImageSource): OverlayPictureRead<Size> =
    readThrough(image) { input ->
      val command =
        listOf(ffprobe, "-v", "error", "-select_streams", "v:0") +
          listOf("-show_entries", "stream=width,height", "-of", "csv=p=0:s=x", input)
      ranToEnd(command) { sizeOfProbe(it.decodeToString()) }
    }

  private fun <T> readThrough(
    image: ImageSource,
    read: (String) -> OverlayPictureRead<T>,
  ): OverlayPictureRead<T> {
    val written =
      (image as? ImageSource.Bytes)?.let { bytes ->
        when (val file = writtenToFile(bytes.bytes)) {
          is OverlayPictureRead.Read -> file.value
          is OverlayPictureRead.Refused -> return file
        }
      }
    val input =
      when (image) {
        is ImageSource.Path -> image.path
        is ImageSource.Uri -> filePathOf(image.uri) ?: image.uri
        is ImageSource.Bytes -> checkNotNull(written).absolutePath
      }

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

  // Runs [command] to its end and hands back what [parse] reads out of its stdout, or why not when it failed, wrote
  // nothing [parse] reads or ran past [timeoutMillis]. Both pipes drain on threads of their own, since a full one blocks
  // the child, so this thread only ever waits, and a wait is what an interrupt breaks. An interrupt stops the child and
  // is thrown on, so a cancelled plan sees its cancellation rather than a picture it could not read.
  @Throws(InterruptedException::class)
  private fun <T> ranToEnd(
    command: List<String>,
    parse: (ByteArray) -> T?,
  ): OverlayPictureRead<T> {
    val name = File(command.first()).name
    val process =
      try {
        ProcessBuilder(command).start()
      } catch (unstarted: IOException) {
        return OverlayPictureRead.Refused(unstarted.message.orEmpty())
      }

    val stdout = ByteArrayOutputStream()
    val stderr = StderrTail()
    val drains =
      listOf(
        drained(process, "stdout") { process.inputStream.transferTo(stdout) },
        drained(process, "stderr") {
          process.errorReader().forEachLine { line -> synchronized(stderr) { stderr.accept(line) } }
        },
      )

    try {
      runCatching { process.outputStream.close() }
      if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        return OverlayPictureRead.Refused("$name was still reading the picture after $timeoutMillis ms.")
      }
      drains.forEach { it.join(FRAME_EXIT_GRACE_MILLIS) }
      val read = stdout.toByteArray().takeIf { process.exitValue() == 0 && it.isNotEmpty() }?.let(parse)
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

// The `WIDTHxHEIGHT` line ffprobe writes for the first video stream, or null for anything else.
internal fun sizeOfProbe(text: String): Size? {
  val (width, height) =
    text
      .lineSequence()
      .firstOrNull { it.isNotBlank() }
      ?.trim()
      ?.split('x')
      ?.mapNotNull { it.toIntOrNull() }
      ?.takeIf { it.size == 2 }
      ?: return null
  return if (width > 0 && height > 0) Size(width, height) else null
}

// Long enough for a large AVIF on a loaded machine, and short of leaving a plan hung on a child that never finishes.
internal const val PICTURE_TIMEOUT_MILLIS: Long = 30_000

private const val SCRATCH_PREFIX = "filmstrip-overlay"

/**
 * The picture a PAM of `RGB_ALPHA` tuples at eight bits holds, as straight ARGB, or null for any other PAM.
 */
internal fun pictureOfPam(pam: ByteArray): BufferedImage? {
  val headerEnd = pam.indexOfHeaderEnd() ?: return null
  val fields =
    pam
      .decodeToString(0, headerEnd)
      .lines()
      .map { it.trim().split(' ', limit = 2) }
      .filter { it.size == 2 }
      .associate { (key, value) -> key to value.trim() }
  val width = fields["WIDTH"]?.toIntOrNull() ?: return null
  val height = fields["HEIGHT"]?.toIntOrNull() ?: return null
  if (fields["DEPTH"] != "4" || fields["MAXVAL"] != "255" || width <= 0 || height <= 0) return null
  val samples = headerEnd + END_HEADER.length
  if (pam.size < samples + width * height * RGBA) return null

  fun sample(at: Int): Int = pam[at].toInt() and BYTE_MASK
  val argb =
    IntArray(width * height) { index ->
      val offset = samples + index * RGBA
      (sample(offset + 3) shl ALPHA_SHIFT) or
        (sample(offset) shl RED_SHIFT) or
        (sample(offset + 1) shl GREEN_SHIFT) or
        sample(offset + 2)
    }
  return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, argb, 0, width) }
}

// Where the header's closing line starts, which is the one line of it that is not a field.
private fun ByteArray.indexOfHeaderEnd(): Int? {
  val marker = END_HEADER.encodeToByteArray()
  val limit = minOf(size, HEADER_LIMIT) - marker.size
  return (0..limit).firstOrNull { start -> marker.indices.all { this[start + it] == marker[it] } }
}

private const val END_HEADER = "ENDHDR\n"
private const val HEADER_LIMIT = 256
private const val RGBA = 4
private const val BYTE_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
