package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.TIMED_OUT
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Runs children past a short deadline, so the stop a deadline sets off is shown to be the same `q`, then terminate
 * sequence a cancelled export goes through.
 */
class ProcessDeadlineTest {
  private val directory = Files.createTempDirectory("filmstrip-process-deadline").toFile()
  private val pidFile = File(directory, "child.pid")
  private val runner = ProcessRunner(stopGrace = GRACE)

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  // sleep never reads its stdin, so it is only gone once the grace for `q` has passed and it was terminated.
  @Test
  fun `a child that ignores q is terminated once its grace has passed`() =
    runTest(timeout = TEST_TIMEOUT) {
      val started = TimeSource.Monotonic.markNow()

      val output = runner.capture(listOf(script("exec sleep 30").path), DEADLINE)

      output.exitCode shouldBe TIMED_OUT
      started.elapsedNow() shouldBeGreaterThanOrEqualTo (DEADLINE + GRACE)
      childIsGone()
    }

  @Test
  fun `a child that answers q is not held for the grace`() =
    runTest(timeout = TEST_TIMEOUT) {
      val started = TimeSource.Monotonic.markNow()

      val code = runner.run(listOf(script("read line; exit 0").path), DEADLINE, onStdout = {}, onStderr = {})

      code shouldBe TIMED_OUT
      started.elapsedNow() shouldBeLessThan (DEADLINE + GRACE)
      childIsGone()
    }

  // The shell runs its sleep as a child of its own and keeps it, so the sleep holds the pipes after the shell is gone.
  // Linux leaves a read on them blocked until the sleep ends.
  @Test
  fun `a child whose own child holds its pipes is stopped with it`() =
    runTest(timeout = TEST_TIMEOUT) {
      val grandchild = File(directory, "grandchild.pid")
      val started = TimeSource.Monotonic.markNow()

      val output =
        runner.capture(listOf(script("sh -c 'echo $$ > \"${grandchild.path}\"; exec sleep 30'; true").path), DEADLINE)

      output.exitCode shouldBe TIMED_OUT
      started.elapsedNow() shouldBeLessThan HELD_PIPES_BOUND
      childIsGone()
      isGone(grandchild)
    }

  @Test
  fun `a child that finishes inside its deadline keeps its exit code and output`() =
    runTest(timeout = TEST_TIMEOUT) {
      val output = runner.capture(listOf(script("echo listed; exit 3").path), DEADLINE)

      output.exitCode shouldBe 3
      output.timedOut shouldBe false
      output.stdout.trim() shouldBe "listed"
    }

  // The script writes its process id and then runs [body] as that same process.
  private fun script(body: String): File =
    File(directory, "child").apply {
      writeText("#!/bin/sh\necho $$ > '${pidFile.path}'\n$body\n")
      setExecutable(true)
    }

  private fun childIsGone() = isGone(pidFile)

  private fun isGone(pidFile: File) {
    val pid = pidFile.readText().trim().toLong()
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false
  }

  private companion object {
    val DEADLINE = 300.milliseconds
    val GRACE = 500.milliseconds
    val TEST_TIMEOUT = 20.seconds

    // The deadline, the stop's grace twice over and room for a loaded machine, and far short of the sleep.
    val HELD_PIPES_BOUND = 5.seconds
  }
}
