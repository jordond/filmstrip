package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.FRAME_EXIT_GRACE_MILLIS
import dev.jordond.filmstrip.ffmpeg.internal.FrameStream
import dev.jordond.filmstrip.ffmpeg.internal.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.ToolchainResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The exit status a frame stream reads back off a real ffmpeg once its frames run out.
 *
 * The status is what tells a render that reached its end from one that failed, since both end the same way on the pipe.
 */
class FrameStreamExitTest {
  private val runner = ProcessRunner()

  @Test
  fun `a render that runs to its end exits with zero`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(generated(seconds = 1))
      try {
        val frame = ByteArray(FRAME_BYTES)
        repeat(RATE) { stream.read(frame) shouldBe true }
        stream.read(frame) shouldBe false

        stream.exitStatus() shouldBe 0
      } finally {
        stream.close()
      }
    }

  // The status is read before a preview ends or wraps, so a child already gone must not cost the grace a live one gets.
  @Test
  fun `a render that has already exited reads back its status at once`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(generated(seconds = 1))
      try {
        val frame = ByteArray(FRAME_BYTES)
        repeat(RATE) { stream.read(frame) shouldBe true }
        stream.read(frame) shouldBe false
        withContext(Dispatchers.IO) { while (stream.isAlive) Thread.sleep(REAP_POLL_MILLIS) }

        val read = TimeSource.Monotonic.markNow()
        stream.exitStatus() shouldBe 0
        val took = read.elapsedNow()
        assertTrue(took < PROMPT, "the status took $took, not inside $PROMPT")
      } finally {
        stream.close()
      }
    }

  @Test
  fun `a missing input exits with a failure and says why`() =
    runTest(timeout = TIMEOUT) {
      val missing = File.createTempFile("filmstrip-missing", ".mp4").also { it.delete() }
      val stream = open(listOf("-i", missing.absolutePath) + RAW_OUTPUT)
      try {
        stream.read(ByteArray(FRAME_BYTES)) shouldBe false

        stream.exitStatus() shouldNotBe 0
        stream.errors() shouldContain missing.name
      } finally {
        stream.close()
      }
    }

  // A child the caller kills exits non-zero as well, so a status read after close would look like a failure.
  @Test
  fun `a stream the caller closed reads back no status`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(endless())
      stream.read(ByteArray(FRAME_BYTES)) shouldBe true
      stream.close()

      stream.exitStatus() shouldBe null
    }

  // A caller can stop reading before the child is done, and the child is then still running when the status is asked.
  @Test
  fun `a stream still running reads back no status`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(endless())
      try {
        stream.read(ByteArray(FRAME_BYTES)) shouldBe true

        stream.exitStatus() shouldBe null
      } finally {
        stream.close()
      }
    }

  private suspend fun open(arguments: List<String>): FrameStream {
    val toolchain = assertIs<ToolchainResult.Available>(ToolchainLocator(FfmpegConfig(), runner).toolchain()).toolchain
    val command = listOf(toolchain.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin") + arguments
    return assertNotNull(runner.frames(command), "ffmpeg did not start")
  }

  private fun generated(seconds: Int): List<String> =
    listOf("-f", "lavfi", "-i", "color=c=red:s=${SIDE}x$SIDE:r=$RATE:d=$seconds") + RAW_OUTPUT

  // No duration, so the source never runs out and the child only stops once it is killed.
  private fun endless(): List<String> = listOf("-f", "lavfi", "-i", "color=c=red:s=${SIDE}x$SIDE:r=$RATE") + RAW_OUTPUT

  private companion object {
    const val SIDE = 2
    const val RATE = 5
    const val FRAME_BYTES = SIDE * SIDE * 4

    val RAW_OUTPUT = listOf("-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1")
    val TIMEOUT = 1.minutes

    // Half the grace leaves room for a loaded machine while a read that waited it out still fails.
    val PROMPT = (FRAME_EXIT_GRACE_MILLIS / 2).milliseconds

    const val REAP_POLL_MILLIS = 10L
  }
}
