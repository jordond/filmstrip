package dev.jordond.filmstrip.ffmpeg.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal actual class ProcessRunner {
  actual suspend fun capture(command: List<String>): ProcessOutput =
    withContext(Dispatchers.IO) {
      val stdout = StringBuilder()
      val stderr = StringBuilder()
      val code =
        try {
          drain(command, stdout::appendLine, stderr::appendLine)
        } catch (cause: IOException) {
          return@withContext ProcessOutput(SPAWN_FAILED, "", cause.message.orEmpty())
        }

      ProcessOutput(code, stdout.toString(), stderr.toString())
    }

  actual suspend fun run(
    command: List<String>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
  ): Int =
    withContext(Dispatchers.IO) {
      try {
        drain(command, onStdout, onStderr)
      } catch (e: IOException) {
        onStderr(e.message.orEmpty())
        SPAWN_FAILED
      }
    }

  // stdout and stderr are drained on their own coroutines because a full pipe blocks the child, and
  // ffmpeg is chatty on stderr while stdout carries the machine-readable progress. They are not
  // merged.
  private suspend fun drain(
    command: List<String>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
  ): Int {
    val process =
      withContext(Dispatchers.IO) {
        ProcessBuilder(command).start()
      }

    try {
      return coroutineScope {
        // inputReader() and errorReader() decode with the native encoding. An InputStreamReader
        // would use Charset.defaultCharset(), which JEP 400 made UTF-8 in JDK 18 whatever the
        // console is, so it mangles output on a non-UTF-8 host.
        val out = launch { runInterruptible { process.inputReader().forEachLine(onStdout) } }
        val err = launch { runInterruptible { process.errorReader().forEachLine(onStderr) } }
        val code = runInterruptible { process.waitFor() }
        out.join()
        err.join()
        code
      }
    } finally {
      if (process.isAlive) stop(process)
    }
  }

  // ffmpeg polls stdin and treats `q` as a request to finish the file it is writing, so the moov
  // atom lands and a partial export is still readable. destroy() on the JVM is TerminateProcess on
  // Windows whatever the flag says, so it is the fallback rather than the mechanism.
  private fun stop(process: Process) {
    runCatching {
      process.outputWriter().apply {
        write("q\n")
        flush()
      }
    }

    if (process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) return

    process.destroy()

    if (process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) return

    process.destroyForcibly()
  }
}

internal actual fun environmentVariable(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

internal actual fun pathEntries(): List<String> =
  System
    .getenv("PATH")
    .orEmpty()
    .split(File.pathSeparatorChar)
    .filter { it.isNotBlank() }

// Windows has no executable bit, so a name on PATH is tried against each PATHEXT entry instead.
internal actual fun executableSuffixes(): List<String> {
  val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
  if (!windows) return listOf("")
  val ext = System.getenv("PATHEXT").orEmpty().ifBlank { ".EXE;.CMD;.BAT" }
  return ext.split(';').filter { it.isNotBlank() }
}

internal actual fun absolutePathOf(path: String): String = File(path).absolutePath
