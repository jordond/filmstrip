package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * A composition with nothing under it, driven a frame at a time.
 *
 * Enough runtime to compose, to start effects and to hand out frames, which is what the ordering
 * rules in this module are about. Nothing here draws, so no UI toolkit is needed to run it.
 *
 * @param scope The test's scope, whose scheduler drives both the recomposer and the effects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ComposeRuntime(
  private val scope: TestScope,
) {
  private val clock = BroadcastFrameClock()
  private val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)
  private val composition = Composition(NoOpApplier, recomposer)

  init {
    scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    scope.runCurrent()
  }

  /**
   * Composes [content] and settles it.
   */
  fun setContent(content: @Composable () -> Unit) {
    composition.setContent(content)
    settle()
  }

  /**
   * Runs everything queued and hands out frames until nothing is left moving.
   */
  fun settle() {
    repeat(FRAMES) {
      Snapshot.sendApplyNotifications()
      scope.runCurrent()
      clock.sendFrame(0L)
      scope.runCurrent()
    }
  }

  fun dispose() {
    composition.dispose()
    recomposer.cancel()
  }

  private companion object {
    const val FRAMES = 4
  }
}

private object NoOpApplier : Applier<Unit> {
  override val current: Unit = Unit

  override fun down(node: Unit): Unit = Unit

  override fun up(): Unit = Unit

  override fun insertTopDown(
    index: Int,
    instance: Unit,
  ): Unit = Unit

  override fun insertBottomUp(
    index: Int,
    instance: Unit,
  ): Unit = Unit

  override fun remove(
    index: Int,
    count: Int,
  ): Unit = Unit

  override fun move(
    from: Int,
    to: Int,
    count: Int,
  ): Unit = Unit

  override fun clear(): Unit = Unit
}
