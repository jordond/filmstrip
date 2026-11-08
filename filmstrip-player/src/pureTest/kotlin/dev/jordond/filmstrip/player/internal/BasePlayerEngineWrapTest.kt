package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.SeekAccuracy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How [BasePlayerEngine] reports the playhead between a loop wrap or a seek being asked for and its seek resolving,
 * whatever the platform reads in between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BasePlayerEngineWrapTest {
  @Test
  fun `a wrap holds the playhead on its landing until its seek resolves`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()
      advanceTimeBy(50.milliseconds)

      val mark = watcher.positions.size
      ticking.wrapTo(2.seconds)
      advanceTimeBy(100.milliseconds)

      val held = watcher.positions.drop(mark)
      (held.size > 1) shouldBe true
      held.forEach { it shouldBe 2.seconds }

      ticking.platform.issued
        .single()
        .complete()
      ticking.position = 2.05.seconds
      advanceTimeBy(50.milliseconds)

      watcher.positions.last() shouldBe 2.05.seconds
    }

  @Test
  fun `a wrap redirected elsewhere holds until it resolves and then reports where it landed`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()

      ticking.wrapTo(2.seconds)
      advanceTimeBy(50.milliseconds)
      watcher.positions.last() shouldBe 2.seconds

      ticking.position = 3.seconds
      ticking.platform.issued
        .single()
        .complete(landed = 3.seconds)
      advanceTimeBy(50.milliseconds)

      watcher.positions.last() shouldBe 3.seconds
    }

  @Test
  fun `a newer seek takes the hold over from a wrap until it resolves`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()

      ticking.wrapTo(2.seconds)
      ticking.seekTo(5.seconds, SeekAccuracy.Exact)
      ticking.position = 4.seconds
      advanceTimeBy(50.milliseconds)
      watcher.positions.last() shouldBe 5.seconds

      ticking.platform.drain()
      ticking.position = 5.05.seconds
      advanceTimeBy(50.milliseconds)
      watcher.positions.last() shouldBe 5.05.seconds
    }

  @Test
  fun `a wrap queued behind a seek in flight holds through that seek's landing`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()

      ticking.seekTo(5.seconds, SeekAccuracy.Exact)
      ticking.wrapTo(2.seconds)
      val mark = watcher.positions.size
      ticking.position = 5.seconds
      ticking.platform.issued
        .first()
        .complete()
      ticking.push(5.1.seconds)
      advanceTimeBy(50.milliseconds)

      watcher.seekCompletions.map { it.position } shouldBe listOf(5.seconds)
      val held = watcher.positions.drop(mark)
      (held.size > 1) shouldBe true
      held.forEach { it shouldBe 2.seconds }

      ticking.platform.issued
        .last()
        .complete()
      ticking.position = 2.05.seconds
      advanceTimeBy(50.milliseconds)
      watcher.positions.last() shouldBe 2.05.seconds
    }

  @Test
  fun `a scrub's settle takes the hold over and holds its own target`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()

      ticking.wrapTo(2.seconds)
      ticking.beginScrub()
      ticking.seekTo(3.seconds, SeekAccuracy.Exact)
      ticking.endScrub()
      ticking.position = 4.seconds
      advanceTimeBy(50.milliseconds)
      watcher.positions.last() shouldBe 3.seconds

      ticking.platform.drain()
      ticking.position = 3.05.seconds
      advanceTimeBy(50.milliseconds)
      watcher.positions.last() shouldBe 3.05.seconds
    }

  @Test
  fun `a scrub that sought nowhere leaves a wrap holding the playhead`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()

      ticking.wrapTo(2.seconds)
      ticking.beginScrub()
      ticking.endScrub()
      advanceTimeBy(50.milliseconds)

      watcher.positions.last() shouldBe 2.seconds
    }

  @Test
  fun `a jump while a wrap holds the playhead holds the jump until the wrap resolves`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()

      ticking.wrapTo(2.seconds)
      ticking.jump(1.seconds)
      watcher.positions.last() shouldBe 1.seconds
      ticking.position = 2.5.seconds
      advanceTimeBy(50.milliseconds)
      watcher.positions.last() shouldBe 1.seconds

      ticking.platform.issued
        .single()
        .complete(landed = 1.seconds)
      ticking.position = 1.05.seconds
      advanceTimeBy(50.milliseconds)
      watcher.positions.last() shouldBe 1.05.seconds
    }

  @Test
  fun `a jump with no seek in flight only reports it`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)

      ticking.jump(1.seconds)
      ticking.position = 3.seconds

      watcher.positions shouldBe listOf(1.seconds)
      ticking.reported shouldBe 3.seconds
    }

  @Test
  fun `dispose releases a wrap still holding the playhead`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()

      ticking.wrapTo(2.seconds)
      ticking.reported shouldBe 2.seconds
      ticking.dispose()
      ticking.position = 4.seconds

      ticking.reported shouldBe 4.seconds
    }

  @Test
  fun `a status change releases a wrap still holding the playhead`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.position = 9.9.seconds
      ticking.play()

      ticking.wrapTo(2.seconds)
      ticking.becomePreparing()
      ticking.position = 4.seconds
      ticking.becomeReady(10.seconds)
      advanceTimeBy(50.milliseconds)

      watcher.positions.last() shouldBe 4.seconds
    }
}
