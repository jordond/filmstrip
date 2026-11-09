package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.test.DEFAULT_MIN_PSNR_DB
import dev.jordond.filmstrip.test.DEFAULT_MIN_SSIM
import dev.jordond.filmstrip.test.TestFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a backend hands the suites that hold a clip's preview to its export: an engine, a written file and the frames
 * read out of it.
 */
interface ClipParityBackend {
  /**
   * Builds a fresh engine holding no composition.
   *
   * Called once per engine a test needs. The suite disposes the engine afterwards.
   *
   * @param scope The dispatcher the suite drives the engine from. Confine platform callbacks to it.
   */
  fun createEngine(scope: CoroutineScope): PlayerEngine

  /**
   * The registered backend, which probes what an export wrote.
   */
  val filmstrip: Filmstrip

  /**
   * This backend's fixture clip, with audio.
   */
  val fixtureClip: MediaSource

  /**
   * The rate the fixture plays at, which is the grid both sides of a comparison read on.
   */
  val frameRate: Int

  /**
   * Writes [composition] through a real export and hands back the file as a source.
   */
  suspend fun exportedSource(composition: Composition): MediaSource

  /**
   * The frame [composition] exports at [position], as tightly packed RGBA_8888.
   */
  suspend fun exportFrame(
    composition: Composition,
    position: Duration,
  ): TestFrame

  /**
   * How close a preview frame and its export have to read, loosened only for what this backend's export path costs.
   */
  val minPsnrDb: Double get() = DEFAULT_MIN_PSNR_DB

  val minSsim: Double get() = DEFAULT_MIN_SSIM

  /**
   * The SSIM a preview frame and the export at output [slot] have to read, [minSsim] on every slot by default. It names
   * the encoder's limit where it overrides this for the slots that encoder writes further off.
   */
  fun minSsimAt(slot: Long): Double = minSsim

  /**
   * How close to a source frame's own time a preview read may land and show the frame either side of it, for a backend
   * that cannot read that finely. It names that backend's limit where it overrides this.
   */
  val previewReadSlack: Duration get() = Duration.ZERO

  /**
   * What both sides of a preview and export comparison are read through, the whole frame by default. It names that
   * backend's limit where it overrides this.
   */
  fun comparedPart(frame: TestFrame): TestFrame = frame

  /**
   * How much further than a slot and an access unit past the shared length this backend's file may run at [speed]. It
   * names that backend's limit where it overrides this.
   */
  fun writtenLengthSlack(speed: Double): Duration = Duration.ZERO

  /**
   * A source whose picture moves from frame to frame, with the fixture's audio.
   */
  suspend fun movingSource(): MediaSource =
    exportedSource(
      Composition(listOf(Track(listOf(Clip(fixtureClip, MOVING_RUN, effects = listOf(MOVING_PAN)))))),
    )
}

/**
 * The run of source every backend's moving source holds, which every fixture is at least as long as.
 */
internal val MOVING_RUN: TimeRange = TimeRange.of(Duration.ZERO, 1_500.milliseconds)

/**
 * The pan a backend draws a moving source under, from a window at one edge of the fixture to one at the other.
 */
internal val MOVING_PAN: KenBurns =
  KenBurns(
    from = NormalizedRect(0f, 0f, 0.4f, 1f),
    to = NormalizedRect(0.6f, 0f, 1f, 1f),
    easing = Easing.Linear,
  )

/**
 * The trim whose file at speed 1 sets the baseline a written length is read against.
 */
internal val BASELINE_TRIM: TimeRange = TimeRange.of(200.milliseconds, 1_300.milliseconds)

/**
 * Checks the file [edit] writes runs [length], past what [BASELINE_TRIM] adds at speed 1.
 *
 * Every backend's audio encoder adds its own tail to a file, a clip at speed 1 included, so what that clip adds is the
 * baseline. Past it a file ends on a whole slot and one more access unit of audio.
 */
internal suspend fun ClipParityBackend.assertWrittenLength(
  source: MediaSource,
  edit: Composition,
  length: Duration,
  speed: Double,
) {
  val unity = Composition(listOf(Track(listOf(Clip(source, BASELINE_TRIM)))))
  val added = writtenLengthOf(unity) - checkNotNull(unity.duration)
  val written = writtenLengthOf(edit)
  withClue("the file at $speed ran $written against a shared length of $length, and speed 1 adds $added") {
    val tolerance = 1.seconds / frameRate + AAC_UNIT + writtenLengthSlack(speed)
    ((written - length - added).absoluteValue <= tolerance).shouldBeTrue()
  }
}

private suspend fun ClipParityBackend.writtenLengthOf(composition: Composition): Duration =
  filmstrip
    .probe(exportedSource(composition))
    .shouldBeInstanceOf<ProbeResult.Success>()
    .info.duration

private val AAC_UNIT = 1.seconds * 1_024 / 48_000
