package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.EngineUnderTest
import dev.jordond.filmstrip.player.contract.Interruption
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * The browser engine wrapped so a contract suite can count what the page underneath it did.
 *
 * Both occasions are raised through the engine's own seam rather than through the browser. A test
 * runner cannot deny autoplay to a page it has already granted it to, and a karma page is not one a
 * test can send to the background, so what is staged here is the call the browser's own listener
 * makes rather than a faked event object.
 *
 * @param scope The dispatcher the engine is confined to.
 * @param drawFirstFrame Draws the frame a new graph owes at a position, answering whether it drew one.
 */
internal class WebEngineUnderTest(
  scope: CoroutineScope,
  drawFirstFrame: suspend (preview: BrowserPreview, at: Duration) -> Boolean = { preview, at ->
    preview.frameAt(at) != null
  },
) : EngineUnderTest {
  override val engine: BrowserPlayerEngine =
    BrowserPlayerEngine(
      parent = scope,
      planner = BrowserPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
      drawFirstFrame = drawFirstFrame,
    )

  override val platformLoads: Int get() = engine.platformLoads

  /**
   * Null, because nothing under this engine holds a timeline of its own.
   *
   * Frames are decoded and composited straight onto a canvas with no player object behind them, so
   * every duration the engine knows is the one its resolved composition carries, and reading one
   * back here would compare that number against itself. The only length the browser measures on its
   * own is what a source container reports, which is one clip's length rather than the
   * composition's.
   */
  override val platformDuration: Duration? get() = null

  override suspend fun awaitQuiet() {
    engine.awaitQuiet()
  }

  override fun stage(interruption: Interruption) {
    when (interruption) {
      Interruption.AutoplayBlocked -> engine.onAutoplayRefused()
      Interruption.AppBackgrounded -> engine.onPageHidden()
      else -> super.stage(interruption)
    }
  }
}
