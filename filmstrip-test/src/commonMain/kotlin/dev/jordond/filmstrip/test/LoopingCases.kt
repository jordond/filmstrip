package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.edit.TimeRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The three looping edits every backend's export test measures, held as data so the four suites
 * build one edit rather than four that happen to agree.
 *
 * A suite takes the trims, offsets and lengths from here, builds the composition with its own
 * fixtures, and reads each level at [Reading.pass] of the laid list, [Reading.into] that pass. The
 * instant and the gain to expect both come off the laid clip, so a schedule that moved takes the
 * reading with it.
 *
 * No two of a track's offset, its pass length and the composition's own length divide evenly, which
 * keeps the last pass cut and every reading clear of a pass boundary. Fixture paths, sample rates
 * and tolerances stay with the suite that measures them.
 */
public object LoopingCases {
  private val BED_FROM = 200.milliseconds
  private val BED_TO = 1_900.milliseconds
  private val LOUD_TO = 1_100.milliseconds
  private val QUIET_FROM = 1_400.milliseconds
  private val QUIET_TO = 2_300.milliseconds
  private val VIDEO_FROM = 100.milliseconds
  private val VIDEO_TO = 1_600.milliseconds

  /**
   * How long the primary runs in the first two cases, which is what fixes the composition's length
   * there.
   */
  public val PRIMARY_RUN: Duration = 7_300.milliseconds

  /**
   * Case one's bed, trimmed off both ends so a pass is neither the source's own length nor a round
   * figure.
   */
  public val BED_TRIM: TimeRange = TimeRange.of(BED_FROM, BED_TO)

  /**
   * The lengths case one's looping track repeats, which is what a pass schedule is derived from.
   */
  public val BED_LENGTHS: List<Duration> = listOf(BED_TO - BED_FROM)

  /**
   * Where case one's bed track opens, which is no multiple of its pass.
   */
  public val BED_START: Duration = 700.milliseconds

  /**
   * Case one's track fade, long enough to cover the first two passes so the ramp is still climbing
   * where pass two opens.
   */
  public val TRACK_FADE: Duration = 3_000.milliseconds

  /**
   * Where case one is read before its bed track opens, and the mix carries the primary alone.
   */
  public val BEFORE_BED: Duration = 300.milliseconds

  /**
   * Case one's readings: a quarter and three quarters of the way up the fade, then the plateau past
   * it, then the pass the run cut short. The two ramp readings sit in different passes, which a fade
   * replayed every pass gets wrong.
   */
  public val BED_READINGS: List<Reading> =
    listOf(
      Reading(pass = 0, into = 750.milliseconds),
      Reading(pass = 1, into = 550.milliseconds),
      Reading(pass = 2, into = 400.milliseconds),
      Reading(pass = 3, into = 1_100.milliseconds),
    )

  /**
   * Where case two's track opens.
   */
  public val PAIR_START: Duration = 500.milliseconds

  /**
   * The first clip of case two's track, which carries whatever level the track holds.
   */
  public val LOUD_TRIM: TimeRange = TimeRange.of(Duration.ZERO, LOUD_TO)

  /**
   * The second clip of case two's track, a different length from the first so a pass is neither of
   * them alone.
   */
  public val QUIET_TRIM: TimeRange = TimeRange.of(QUIET_FROM, QUIET_TO)

  /**
   * The lengths case two's looping track repeats, in the order it lays them.
   */
  public val PAIR_LENGTHS: List<Duration> = listOf(LOUD_TO, QUIET_TO - QUIET_FROM)

  /**
   * The volume the second clip of case two carries. Far enough under the first to tell the two apart
   * at a glance, and far enough off zero that a clip which arrived muted is not mistaken for it.
   */
  public const val QUIET_VOLUME: Float = 0.4f

  /**
   * Case two's readings: the loud clip and the quiet one inside pass two, the quiet one again a pass
   * later, then the loud clip the run cut. The first two are the pair a level comparison is made on,
   * so they lead the list.
   */
  public val PAIR_READINGS: List<Reading> =
    listOf(
      Reading(pass = 2, into = 500.milliseconds),
      Reading(pass = 3, into = 600.milliseconds),
      Reading(pass = 5, into = 500.milliseconds),
      Reading(pass = 6, into = 500.milliseconds),
    )

  /**
   * Case three's looping video primary, trimmed off both ends.
   */
  public val VIDEO_TRIM: TimeRange = TimeRange.of(VIDEO_FROM, VIDEO_TO)

  /**
   * The lengths case three's looping primary repeats.
   */
  public val VIDEO_LENGTHS: List<Duration> = listOf(VIDEO_TO - VIDEO_FROM)

  /**
   * How long the audio-only track case three lays under its primary runs. It outlasts the primary,
   * so it is what fixes the composition's length.
   */
  public val UNDERLAY_RUN: Duration = 5_300.milliseconds

  /**
   * Case three's readings, both a pass or more past the first, so a primary that played once and
   * stopped reads nothing there.
   */
  public val VIDEO_READINGS: List<Reading> =
    listOf(
      Reading(pass = 2, into = 400.milliseconds),
      Reading(pass = 3, into = 400.milliseconds),
    )

  /**
   * Where a level is read: which laid pass, and how far into that pass.
   *
   * @property pass The index into the laid list, counting every clip of every repeat.
   * @property into How far into that pass the reading sits, on the pass's own clock.
   */
  public data class Reading(
    public val pass: Int,
    public val into: Duration,
  )
}
