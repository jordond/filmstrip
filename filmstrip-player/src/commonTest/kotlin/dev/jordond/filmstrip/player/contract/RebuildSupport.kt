package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.fail
import kotlin.time.Duration

/**
 * This composition with its first clip cut in two on the output slot nearest its middle at [frameRate].
 *
 * The timeline changes, so a backend rebuilds its graph for it, and the duration stays what it was, so a playhead held
 * across the rebuild has the same slots to land on.
 */
internal fun EditComposition.cutInTwo(frameRate: Int): EditComposition {
  val clip = clips.firstOrNull() ?: fail("The fixture has no clip to cut.")
  val trim = clip.trim ?: fail("The fixture's first clip has no trim to cut.")
  val end = trim.endExclusive ?: fail("The fixture's first clip has no end to cut at.")
  val middle = trim.start + outputSlotTime(outputSlotOf((end - trim.start) / 2, frameRate), frameRate)
  val halves = listOf(clip.withTrim(TimeRange.of(trim.start, middle)), clip.withTrim(TimeRange.of(middle, end)))
  return withClips(halves + clips.drop(1))
}

/**
 * This composition with its first clip trimmed to [length] and every later clip on its track dropped.
 */
internal fun EditComposition.shortenedTo(length: Duration): EditComposition {
  val clip = clips.firstOrNull() ?: fail("The fixture has no clip to shorten.")
  val start = clip.trim?.start ?: Duration.ZERO
  return withClips(listOf(clip.withTrim(TimeRange.of(start, start + length))))
}

/**
 * The last playhead reading delivered after the first [from] readings, failing when there is none.
 *
 * A rebuild moves the playhead onto a fresh graph, so an engine that reports nothing there leaves a listener reading
 * the old one.
 */
internal fun ContractRecorder.reportedSince(from: Int): Duration =
  playheads.drop(from).lastOrNull() ?: fail("The engine reported no playhead after the rebuild.")

/**
 * How many playheads had been delivered when the engine first went to [PlaybackStatus.Preparing] after [mark], failing
 * when it never did.
 *
 * A reading at or past this index comes from the rebuilt graph and any reading before it from the graph it replaced.
 */
internal fun ContractRecorder.playheadsBeforeRebuild(mark: ContractMark): Int {
  val preparing = statesSince(mark).indexOfFirst { it.status == PlaybackStatus.Preparing }
  if (preparing < 0) fail("The engine did not go back through Preparing for the rebuild.")
  return playheadsAtStates[mark.states + preparing]
}

/**
 * Seeks exactly to [to] and suspends until [recorder] has heard the seek complete.
 */
internal suspend fun PlayerEngine.seekAndWait(
  recorder: ContractRecorder,
  to: Duration,
) {
  val before = recorder.seekCompletions.size
  seekTo(to, SeekAccuracy.Exact)
  awaitContract("the seek to $to to complete") { recorder.seekCompletions.size > before }
}

/**
 * Loads [composition], waits for the engine to become seekable and answers the duration it then reports.
 */
internal suspend fun PlayerEngine.loadSeekable(
  recorder: ContractRecorder,
  composition: EditComposition,
): Duration {
  awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
  awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
  return recorder.lastState.duration ?: fail("The engine reported no duration to seek within.")
}
