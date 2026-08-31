package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Every process this backend spawns is one it also reaps.
 *
 * The pump is a child of the test JVM, so the claim is made against the real process table rather
 * than against a flag the engine keeps: a pid that outlives the engine holds a decoder open and goes
 * on writing into a pipe nobody reads.
 *
 * Counts are taken against a baseline read before the engine exists, because the whole module's
 * suites share one JVM and whichever ran first is still shutting its own children down.
 */
class JvmProcessHygieneTest {
  @Test
  fun `releasing the engine leaves no pump running`() =
    contractTest { scope ->
      val baseline = pumps()
      val subject = JvmEngineUnderTest(scope)
      var running: List<ProcessHandle> = emptyList()

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }
        subject.engine.play()
        awaitContract("a pump to be running") { (pumps() - baseline).isNotEmpty() }
        running = pumps() - baseline
      }

      running.shouldNotBeEmpty()

      // withEngine disposes on its way out, and the pumps it was holding are gone by the time it
      // returns rather than eventually.
      running.filter { it.isAlive }.map { it.pid() } shouldBe emptyList()
    }

  // A seek is a respawn on this backend, so a burst of them is where a leak compounds: one process
  // per seek would be a dozen decoders still running by the end of a drag.
  @Test
  fun `a burst of seeks leaves one pump at a time`() =
    contractTest { scope ->
      val baseline = pumps()
      val subject = JvmEngineUnderTest(scope)

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }

        val before = recorder.seekCompletions.size
        PROBE_POSITIONS.forEach { subject.engine.seekTo(it, SeekAccuracy.Exact) }
        awaitContract("every seek to complete") { recorder.seekCompletions.size - before >= PROBE_POSITIONS.size }
        settle()

        (pumps() - baseline).map { it.pid() }.size shouldBe 1
      }

      settle()
      (pumps() - baseline).shouldBeEmpty()
    }

  private fun pumps(): List<ProcessHandle> = runningPumps()
}
