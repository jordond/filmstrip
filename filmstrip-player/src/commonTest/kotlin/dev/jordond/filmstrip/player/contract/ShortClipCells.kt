package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.NEAREST_FRAME_TIE
import kotlinx.coroutines.CancellationException
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a cell reads: the [edit], the source frame each slot should show, the speed the file's length is read at when
 * the cell holds one, and how many frames the file holds when that is not every slot of the edit.
 */
internal class Cell(
  val edit: Composition,
  val reads: List<Read>,
  val writtenAtSpeed: Double? = null,
  val writtenFrames: Long? = null,
)

internal class Read(
  val slot: Long,
  val sourceFrame: Long,
)

internal class Cut(
  val trim: TimeRange,
  val speed: Double,
)

internal class PlacedClip(
  val cut: Cut,
  val span: TimeRange,
) {
  fun clipOf(source: MediaSource) = Clip(source, cut.trim, speed = cut.speed)
}

/**
 * A trim of [start] to [end] past wherever the cell opens it.
 */
internal class PlacedCut(
  val start: Duration,
  val end: Duration,
  val speed: Double,
) {
  fun at(opening: Duration) = Cut(TimeRange.of(opening + start, opening + end), speed)
}

internal val HALF_SECOND = 500.milliseconds

/**
 * The plain clips a run opens and closes with in the short clip cells, each half a second of the source at speed 1.
 */
internal val OPENING = Cut(TimeRange.of(Duration.ZERO, HALF_SECOND), 1.0)
internal val CLOSING = Cut(TimeRange.of(HALF_SECOND, HALF_SECOND * 2), 1.0)

internal const val SPED_UP = 1.5

/**
 * Runs each named side of a cell and fails afterwards with every side that failed, so one red side leaves the others
 * read.
 */
internal suspend fun runSides(vararg sides: Pair<String, suspend () -> Unit>) {
  val failures =
    sides.mapNotNull { (name, side) ->
      try {
        side()
        null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failed: Throwable) {
        "$name: ${failed.message}"
      }
    }
  if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
}

/**
 * [cuts] laid end to end from zero, each over the output length its trim plays for at its speed.
 */
internal fun runOf(vararg cuts: Cut): List<PlacedClip> {
  var opens = Duration.ZERO
  return cuts.map { cut ->
    val closes = opens + outputLengthOf(checkNotNull(cut.trim.duration), cut.speed)
    PlacedClip(cut, TimeRange.of(opens, closes)).also { opens = closes }
  }
}

/**
 * One track of [run], every clip cut from [source].
 */
internal fun editOf(
  source: MediaSource,
  run: List<PlacedClip>,
) = Composition(listOf(Track(run.map { it.clipOf(source) })))

/**
 * Whether [time] sits within [NEAREST_FRAME_TIE] of half way between the two frames either side of it, at [frameRate].
 */
internal fun sitsOnATie(
  time: Duration,
  frameRate: Int,
): Boolean {
  val frame = 1.seconds / frameRate
  val before = frame * (time / frame).toLong().toDouble()
  return ((time - before) - (before + frame - time)).absoluteValue <= NEAREST_FRAME_TIE * 2
}
