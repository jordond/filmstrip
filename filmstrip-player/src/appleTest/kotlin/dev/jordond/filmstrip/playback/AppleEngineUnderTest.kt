package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.playback.contract.EngineUnderTest
import dev.jordond.filmstrip.playback.contract.Interruption
import dev.jordond.filmstrip.playback.internal.AvPlayerEngine
import dev.jordond.filmstrip.playback.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * The Apple engine wrapped so a contract suite can count what AVFoundation underneath it did.
 *
 * Everything the suite reads off the engine is the same on both Apple platforms. Staging is the one
 * seam that is not, since the notifications that take playback away are macOS's and iOS's own, so it
 * arrives as [raise] rather than as an overridden method.
 *
 * @param scope The dispatcher the engine is confined to.
 * @param raise Posts the notification the system posts for one occasion. It is handed the engine
 *   because a rate change is scoped to the player that posted it.
 */
internal class AppleEngineUnderTest(
  scope: CoroutineScope,
  private val raise: (AvPlayerEngine, Interruption) -> Unit,
) : EngineUnderTest {
  override val engine: AvPlayerEngine =
    AvPlayerEngine(
      parent = scope,
      planner = AvPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override val platformLoads: Int get() = engine.platformLoads

  override val reportsPlatformDuration: Boolean = true

  override val platformDuration: Duration? get() = engine.platformDuration

  override fun stage(interruption: Interruption) {
    raise(engine, interruption)
  }
}
