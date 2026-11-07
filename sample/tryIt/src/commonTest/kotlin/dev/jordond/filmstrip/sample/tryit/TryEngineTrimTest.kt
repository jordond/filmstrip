package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a change of the trim does to the source time on screen, and to the status while the player catches up.
 */
class TryEngineTrimTest {
  private val source = MediaSource.ofUri("blob:beach")

  private fun TestScope.watching(player: FakePlayer): TryEngine {
    val engine =
      TryEngine(backgroundScope, exporter = { _, _ -> flowOf() }, loadClip = { source }, save = {}, release = {})
    engine.load()
    runCurrent()
    engine.watch(player)
    runCurrent()
    return engine
  }

  @Test
  fun `turning the trim on keeps the source time on screen`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)
      player.playhead.value = 5.seconds
      runCurrent()

      engine.apply(Control.SetSteps(Steps(trim = true)))
      runCurrent()

      player.edits.single().startAt shouldBe 3.seconds
      statusOf(engine.state.value).position shouldBe 5.seconds
    }

  @Test
  fun `turning the trim off keeps the source time on screen`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)
      engine.apply(Control.SetSteps(Steps(trim = true)))
      runCurrent()
      player.playhead.value = 3.seconds
      runCurrent()

      engine.apply(Control.SetSteps(Steps()))
      runCurrent()

      player.edits.last().startAt shouldBe 5.seconds
      statusOf(engine.state.value).position shouldBe 5.seconds
    }

  @Test
  fun `turning the trim on outside its range lands on the nearest end`() =
    runTest {
      val early = fakePlayer(source)
      val earlyEngine = watching(early)
      early.playhead.value = 1.seconds
      runCurrent()
      earlyEngine.apply(Control.SetSteps(Steps(trim = true)))
      runCurrent()
      early.edits.single().startAt shouldBe Duration.ZERO
      statusOf(earlyEngine.state.value).position shouldBe TRIM_RANGE.start

      val late = fakePlayer(source)
      val lateEngine = watching(late)
      late.playhead.value = 10.seconds
      runCurrent()
      lateEngine.apply(Control.SetSteps(Steps(trim = true)))
      runCurrent()
      late.edits.single().startAt shouldBe TRIM_RANGE.endInclusive - TRIM_RANGE.start
      statusOf(lateEngine.state.value).position shouldBe TRIM_RANGE.endInclusive
    }

  @Test
  fun `the old edit's playhead does not reach the status while the trim loads`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)
      player.playhead.value = 5.seconds
      runCurrent()
      val gate = CompletableDeferred<Unit>()
      player.loadGate = gate

      engine.apply(Control.SetSteps(Steps(trim = true)))
      runCurrent()
      player.playhead.value = 5100.milliseconds
      runCurrent()
      statusOf(engine.state.value).position shouldBe 5.seconds

      gate.complete(Unit)
      runCurrent()
      player.playhead.value = 3100.milliseconds
      runCurrent()
      statusOf(engine.state.value).position shouldBe 5100.milliseconds
    }

  @Test
  fun `another step pressed while the trim loads keeps its landing`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)
      player.playhead.value = 5.seconds
      runCurrent()
      player.loadGate = CompletableDeferred()

      engine.apply(Control.SetSteps(Steps(trim = true)))
      runCurrent()
      engine.apply(Control.SetSteps(Steps(trim = true, grade = true)))
      runCurrent()

      player.edits.map { it.startAt } shouldContainExactly listOf(3.seconds, 3.seconds)
    }

  @Test
  fun `a step that leaves the trim alone holds the playhead where the player has it`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)
      player.playhead.value = 5.seconds
      runCurrent()

      engine.apply(Control.SetSteps(Steps(grade = true)))
      runCurrent()

      player.edits.single().startAt.shouldBeNull()
      player.edits.single().composition shouldBe beachEdit(source, Steps(grade = true))
    }
}
