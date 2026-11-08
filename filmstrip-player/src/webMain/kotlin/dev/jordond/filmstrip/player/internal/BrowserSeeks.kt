package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Lands the browser engine's seeks, each against the serial it was issued under.
 *
 * Nothing is presented, so there is no decoder to wait on: the next frame the surface asks for is drawn from wherever
 * the playhead now is. A landing that finds a newer seek, a rebuild, a failure or a release has bumped the serial since
 * it was issued writes nothing.
 *
 * Not thread safe. Everything is confined to [scope], like the engine that owns it.
 *
 * @param scope The engine's scope.
 * @param guard The engine's wrap guard, shared with its transport.
 * @param transport Fences each landing and restarts playback from it.
 * @param preview The preview standing now, if any.
 * @param resolved The composition loaded now, if any.
 * @param clock The clock counting now.
 * @param loopRange The loop range set now, if any.
 * @param isWrapSeek Whether the seek being issued is a loop wrap.
 */
internal class BrowserSeeks(
  private val scope: CoroutineScope,
  private val guard: WrapGuard,
  private val transport: BrowserTransport,
  private val preview: () -> BrowserPreview?,
  private val resolved: () -> ResolvedComposition?,
  private val clock: () -> PreviewClock,
  private val loopRange: () -> TimeRange?,
  private val isWrapSeek: () -> Boolean,
) {
  private var serial = 0

  /**
   * Moves the playhead to an output slot, the one [position] rounds to for [SeekAccuracy.Exact].
   *
   * [SeekAccuracy.Nearest] lands on the slot of the sync sample at or before the target, which is the same sample a
   * decoder would have had to start from anyway. Every seek is put to the guard. A wrap that lands after the loop range
   * changed moves on to the first slot of the range standing then, and reports that as its landing.
   */
  fun seek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    val target = preview()
    val composition = resolved()
    if (target == null || composition == null) {
      onComplete(position)
      return
    }

    guard.seekIssued(wrap = isWrapSeek())
    val seek = ++serial
    transport.fence(
      scope.launch {
        val requested = position.coerceAtLeast(Duration.ZERO).coerceAtMost(composition.duration)
        val sent =
          when (accuracy) {
            SeekAccuracy.Exact -> composition.seekLandingOf(requested)
            SeekAccuracy.Nearest -> composition.seekLandingOf(target.syncSampleAt(requested))
          }
        if (seek != serial) return@launch
        val redirect =
          guard.seekLanded(
            loopRange()?.let { loopWrapOf(it, composition.duration, composition.frameRate) },
          )
        // A redirect is one more exact seek, which on this backend lands as soon as the clock moves.
        if (redirect != null) guard.seekLanded(null)
        val landing = redirect?.let { outputSlotTime(it, composition.frameRate) } ?: sent

        transport.clearEnd()
        // Everything decoded ahead of the old playhead is decoded for a position nobody is going to
        // ask for now, so it is closed here rather than left to fall out of the window one frame at a
        // time.
        target.flush()
        clock().moveTo(landing)
        if (transport.wantsPlayback) transport.launchStart { landing }
        onComplete(landing)
      },
    )
  }

  /**
   * Makes every seek in flight stale, so its landing writes nothing.
   */
  fun invalidate() {
    serial++
  }
}
