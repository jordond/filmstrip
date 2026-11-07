package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.player.contract.EngineUnderTest
import dev.jordond.filmstrip.player.contract.Interruption
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.previewItem
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.Foundation.NSURL
import kotlin.time.Duration

/**
 * The Apple engine wrapped so a contract suite can count what AVFoundation underneath it did.
 *
 * Everything the suite reads off the engine is the same on both Apple platforms. Staging is the one
 * seam that is not, since the notifications that take playback away are macOS's and iOS's own, so it
 * arrives as [raise] rather than as an overridden method.
 *
 * @param scope The dispatcher the engine is confined to.
 * @param itemOf Builds the item a lowered composition plays through.
 * @param raise Posts the notification the system posts for one occasion. It is handed the engine
 *   because a rate change is scoped to the player that posted it.
 */
internal class AppleEngineUnderTest(
  scope: CoroutineScope,
  itemOf: (AvComposition) -> AVPlayerItem = ::previewItem,
  private val raise: (AvPlayerEngine, Interruption) -> Unit,
) : EngineUnderTest {
  override val engine: AvPlayerEngine =
    AvPlayerEngine(
      parent = scope,
      planner = AvPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
      muted = true,
      itemOf = itemOf,
    )

  override val platformLoads: Int get() = engine.platformLoads

  override val reportsPlatformDuration: Boolean = true

  override val platformDuration: Duration? get() = engine.platformDuration

  override suspend fun awaitQuiet() {
    engine.fence.awaitDrained()
  }

  override fun stage(interruption: Interruption) {
    raise(engine, interruption)
  }
}

/**
 * An Apple engine whose first item reads a file that isn't there, and whose later items are the real ones.
 *
 * AVFoundation fails that item on its own, past anything the planner checks.
 */
internal fun appleEngineFailingItsFirstItem(scope: CoroutineScope): AppleEngineUnderTest {
  var failed = false
  return AppleEngineUnderTest(
    scope = scope,
    itemOf = { composition ->
      if (failed) {
        previewItem(composition)
      } else {
        failed = true
        missingFileItem()
      }
    },
    raise = { _, interruption -> throw NotImplementedError("This engine raises nothing for $interruption.") },
  )
}

/**
 * An item over a file that isn't there, which AVFoundation fails on its own.
 */
internal fun missingFileItem(): AVPlayerItem =
  AVPlayerItem(asset = AVURLAsset(NSURL.fileURLWithPath(appleFixtureClip() + ".absent"), null))
