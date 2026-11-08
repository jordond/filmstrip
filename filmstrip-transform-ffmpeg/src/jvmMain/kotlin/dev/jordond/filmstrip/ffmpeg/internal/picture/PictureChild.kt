package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.ffmpeg.internal.process.FRAME_EXIT_GRACE_MILLIS
import dev.jordond.filmstrip.ffmpeg.internal.process.FRAME_STOP_GRACE_MILLIS
import dev.jordond.filmstrip.ffmpeg.internal.process.StderrTail
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one ffmpeg child of a picture read to its end and hands back what it wrote, or why not.
 *
 * [stdin] writes the child's standard input on a thread of its own and the pipe is closed after it, or the pipe is
 * closed at once when it is null. Standard output and standard error drain on threads of their own too, since a full
 * pipe blocks the child, so the calling thread only ever waits, and a wait is what an interrupt breaks. [collect] runs
 * on the stdout thread and may stop before the end, since the pipe is closed once it returns and a child still
 * writing then fails and exits. [parse] runs on the calling thread once the child has exited.
 *
 * The read is refused when the child fails, writes nothing [collect] and [parse] read, or runs past [deadline], a
 * System.nanoTime, which is [timeoutMillis] after the read it belongs to started. An interrupt stops the child and is
 * thrown on, so a cancelled plan sees its cancellation rather than a picture it could not read.
 */
@Throws(InterruptedException::class)
internal fun <R : Any, T> ranToEnd(
  command: List<String>,
  deadline: Long,
  timeoutMillis: Long,
  stdin: ((OutputStream) -> Unit)? = null,
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
    listOfNotNull(
      drained(process, "stdout") { process.inputStream.use { stdout.set(collect(it)) } },
      drained(process, "stderr") {
        process.errorReader().forEachLine { line -> synchronized(stderr) { stderr.accept(line) } }
      },
      stdin?.let { write -> drained(process, "stdin") { process.outputStream.buffered().use(write) } },
    )

  try {
    if (stdin == null) runCatching { process.outputStream.close() }
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
// finish. An interrupt that led here, or lands during a wait, is held back across the waits, which would otherwise
// throw at once and skip every grace period, and put back afterwards.
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
    // Best effort: a drain gets one join, and an interrupt during it ends that drain's wait early. The
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
