package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.startTick
import kotlin.time.Duration

/**
 * The live inputs of one running graph, each listening on its own port and writing once [start] is called.
 *
 * A feed writes whether or not ffmpeg has connected yet, reads nothing back, and ends on its own when ffmpeg goes.
 * Nothing it does waits on anything a [close] cannot break.
 */
internal interface LiveFeeds {
  /**
   * What ffmpeg opens to read [input].
   */
  fun urlOf(input: InputSource.Live): String

  /**
   * Starts each feed on a daemon thread of its own.
   */
  fun start()

  /**
   * What went wrong writing a plane, or null while nothing has.
   *
   * A feed that fails closes its socket, and a graph takes that as a stream that ended, so an exit code of zero does
   * not rule this out. ffmpeg going away is a normal end and leaves this null.
   */
  fun failure(): String?

  /**
   * Stops every feed and closes its sockets. Idempotent, and bounded, so it holds up no cancel.
   */
  fun close()
}

/**
 * One live input a graph reads, and the tick it opens on.
 */
internal class LiveFeedRequest(
  val source: InputSource.Live,
  val startTick: Int,
)

/**
 * Whether the listeners a graph's live inputs need could be opened.
 */
internal sealed interface LiveBinding {
  class Bound(
    val feeds: LiveFeeds,
  ) : LiveBinding

  class Refused(
    val reason: String,
  ) : LiveBinding
}

/**
 * Opens one loopback listener per request, or none of them when any one is refused.
 *
 * A refusal is how a sandboxed host reads here.
 */
internal expect fun bindLiveFeeds(requests: List<LiveFeedRequest>): LiveBinding

/**
 * Which form of a plan runs, and the feeds behind it when that form reads them.
 *
 * @property invocation The form to run.
 * @property feeds The bound feeds, or null when the form reads none.
 * @property fallback Why a plan carrying live planes runs its drawn form, for the diagnostics.
 */
internal class ChosenForm(
  val invocation: Invocation,
  val feeds: LiveFeeds?,
  val fallback: String?,
)

/**
 * Binds the feeds of [planned]'s live form for a window opening at [windowOpensAt], or settles on its drawn form.
 *
 * Both forms write the same samples, so a refused bind costs speed and nothing else.
 */
internal fun chooseForm(
  planned: Invocation,
  windowOpensAt: Duration,
  bind: (List<LiveFeedRequest>) -> LiveBinding,
): ChosenForm {
  val live = planned.liveForm ?: return ChosenForm(planned, null, planned.liveFallback)
  val requests =
    live.inputs
      .mapNotNull { it.source as? InputSource.Live }
      .map { LiveFeedRequest(it, it.startTick(windowOpensAt)) }
  return when (val binding = bind(requests)) {
    is LiveBinding.Bound -> ChosenForm(live, binding.feeds, null)
    is LiveBinding.Refused -> ChosenForm(planned, null, loopbackRefused(binding.reason))
  }
}

private fun loopbackRefused(reason: String): String =
  "A loopback listener was refused ($reason), so a look draws its map with its own graph."

/**
 * The exit status a stream reports when its feed failed, whatever ffmpeg made of the stream that ended.
 */
internal const val LIVE_FEED_FAILED: Int = -2

/**
 * The message an export or a preview fails with when a live input could not be written.
 */
internal fun liveFeedFailure(reason: String): String = "A live input failed while the graph was reading it: $reason"
