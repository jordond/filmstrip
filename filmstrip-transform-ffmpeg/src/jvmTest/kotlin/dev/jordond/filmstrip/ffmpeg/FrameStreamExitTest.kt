package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.process.FrameStream
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

/**
 * The exit status a frame stream reads back off a real ffmpeg once its frames run out.
 *
 * The status is what tells a render that reached its end from one that failed, since both end the same way on the pipe.
 */
class FrameStreamExitTest {
  private val runner = ProcessRunner()

  @Test
  fun `a child that cannot be started throws with the reason the system gave`() =
    runTest(timeout = TIMEOUT) {
      val missing = File(System.getProperty("java.io.tmpdir"), "filmstrip-no-such-ffmpeg").path

      val failure = assertFailsWith<IOException> { runner.frames(listOf(missing)) }

      failure.message.orEmpty() shouldContain missing
    }

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
    val toolchain =
      ToolchainLocator(
        FfmpegConfig(),
        runner,
      ).toolchain().shouldBeInstanceOf<ToolchainResult.Available>().toolchain
    val command = listOf(toolchain.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin") + arguments
    return runner.frames(command)
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
  }
}
