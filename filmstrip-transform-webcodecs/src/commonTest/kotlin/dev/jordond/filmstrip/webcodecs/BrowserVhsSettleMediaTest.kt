package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapePictureRamp
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.effects.filters.tape.TapeSettle
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.TrackingSettle
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.webcodecs.internal.clipFrameError
import dev.jordond.filmstrip.webcodecs.internal.stageFrameOn
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Reads a [Vhs] whose band rolls from, and whose strength settles from, the first frame of a clip that opens late,
 * through the whole pipeline, against [TapePictureSignal].
 */
class BrowserVhsSettleMediaTest : BrowserTapePictureFrames() {
  @Test
  fun aRollingBandAnchoredToAClipThatOpensLateIsAtTheTopOnItsFirstFrame() =
    runTest {
      val clip = rampClip()
      val anchored = afterAnother(clip, ANCHORED_LATE)
      val picture = afterAnother(clip, null)

      listOf(OPENING_SLOT, OPENING_SLOT + FRAMES / 2).forEach { slot ->
        val time = outputSlotTime(slot, FRAME_RATE)
        val tick = TapePictureSignal.tickAt(time.toDouble(DurationUnit.SECONDS))

        withClue("slot $slot") {
          worstCodes(
            drawn(anchored, at = time),
            drawn(picture, at = time),
            ANCHORED_LATE,
            tick,
            TapePictureParams.of(ANCHORED_LATE, OPENS),
          ) shouldBeLessThan SIGNAL_CODES
        }
      }
      val first = TapePictureSignal.tickAt(outputSlotTime(OPENING_SLOT, FRAME_RATE).toDouble(DurationUnit.SECONDS))
      val bottom = TapePictureSignal.virtualRow(HEIGHT - 1, HEIGHT)
      TapePictureSignal.trackingWeight(
        ANCHORED_LATE.seed,
        first,
        bottom,
        TapePictureParams.of(ANCHORED_LATE, OPENS),
      ) shouldBeGreaterThan MIDDLE
      TapePictureSignal.trackingWeight(
        ANCHORED_LATE.seed,
        first,
        bottom,
        TapePictureParams.of(ANCHORED_LATE, Duration.ZERO),
      ) shouldBe 0.0
    }

  @Test
  fun aRollingBandSetToBeAtTheTopSixFramesInReadsTheSignalOnThatFrame() =
    runTest {
      val clip = rampClip()
      val anchored = afterAnother(clip, ANCHORED_AFTER)
      val picture = afterAnother(clip, null)

      listOf(OPENING_SLOT, OPENING_SLOT + ANCHOR_FRAMES).forEach { slot ->
        val time = outputSlotTime(slot, FRAME_RATE)
        val tick = TapePictureSignal.tickAt(time.toDouble(DurationUnit.SECONDS))

        withClue("slot $slot") {
          worstCodes(
            drawn(anchored, at = time),
            drawn(picture, at = time),
            ANCHORED_AFTER,
            tick,
            TapePictureParams.of(ANCHORED_AFTER, OPENS),
          ) shouldBeLessThan SIGNAL_CODES
        }
      }
      val onIt =
        TapePictureSignal.tickAt(
          outputSlotTime(OPENING_SLOT + ANCHOR_FRAMES, FRAME_RATE).toDouble(DurationUnit.SECONDS),
        )
      TapePictureSignal.trackingWeight(
        ANCHORED_AFTER.seed,
        onIt,
        TapePictureSignal.virtualRow(HEIGHT - 1, HEIGHT),
        TapePictureParams.of(ANCHORED_AFTER, OPENS),
      ) shouldBeGreaterThan MIDDLE
    }

  @Test
  fun aSettlingBandReadsTheSignalAtTheStartTheMiddleAndTheEndOfItsSettleAndAfterIt() =
    runTest {
      val clip = rampClip()
      val settling = afterAnother(clip, SETTLING)
      val picture = afterAnother(clip, null)
      val params = TapePictureParams.of(SETTLING, OPENS)
      val strengths = mutableListOf<Double>()

      SETTLE_SLOTS.forEach { slot ->
        val time = outputSlotTime(slot, FRAME_RATE)
        val tick = TapePictureSignal.tickAt(time.toDouble(DurationUnit.SECONDS))
        strengths += TapePictureSignal.bandSettle(tick, params)

        withClue("slot $slot") {
          worstCodes(drawn(settling, at = time), drawn(picture, at = time), SETTLING, tick, params) shouldBeLessThan
            SIGNAL_CODES
        }
      }
      strengths.first() shouldBe 1.0
      strengths[1] shouldBeGreaterThan 0.3
      strengths[1] shouldBeLessThan 0.7
      strengths[2] shouldBe 0.0
      strengths[3] shouldBe 0.0
    }

  @Test
  fun aSettlingLookReadsTheSignalAtTheStartTheMiddleAndTheEndOfItsSettleAndAfterIt() =
    runTest {
      val clip = rampClip()
      val settling = afterAnother(clip, LOCKING)
      val picture = afterAnother(clip, null)
      val ramp = TapePictureRamp.of(LOCKING, TimeRange(outputSlotTime(OPENING_SLOT, FRAME_RATE))).shouldNotBeNull()
      val levels = mutableListOf<Double>()

      LOCK_SLOTS.forEach { slot ->
        val time = outputSlotTime(slot, FRAME_RATE)
        val tick = TapePictureSignal.tickAt(time.toDouble(DurationUnit.SECONDS))
        levels += ramp.levelAt(tick)

        withClue("slot $slot") {
          val params = LOCKING.pictureParams(ramp.levelAt(tick), outputSlotTime(OPENING_SLOT, FRAME_RATE))
          worstCodes(drawn(settling, at = time), drawn(picture, at = time), LOCKING, tick, params) shouldBeLessThan
            SIGNAL_CODES
        }
      }
      levels[0] shouldBe 1.0
      levels[1] shouldBeGreaterThan 0.3.toFloat().toDouble()
      levels[1] shouldBeLessThan 1.0
      levels[2] shouldBe 0.3.toFloat().toDouble()
      levels[3] shouldBe 0.3.toFloat().toDouble()
    }

  @Test
  fun aSettleOnTheCompositionCountsFromItsFirstFrame() =
    runTest {
      val clip = rampClip()
      val ramp = TapePictureRamp.of(LOCKING, TimeRange(Duration.ZERO)).shouldNotBeNull()

      listOf(0L, 3L, 6L, 9L).forEach { slot ->
        val time = outputSlotTime(slot, FRAME_RATE)
        val tick = TapePictureSignal.tickAt(time.toDouble(DurationUnit.SECONDS))
        val params = LOCKING.pictureParams(ramp.levelAt(tick), Duration.ZERO)

        withClue("slot $slot") {
          worstCodes(
            drawn(edit(Clip(clip), listOf(LOCKING)), at = time),
            drawn(edit(Clip(clip)), at = time),
            LOCKING,
            tick,
            params,
          ) shouldBeLessThan
            SIGNAL_CODES
        }
      }
    }

  // Two of [clip] one after the other, the second with [look] when it has one.
  private fun afterAnother(
    clip: MediaSource.Bytes,
    look: Vhs?,
  ) = Composition(
    tracks = listOf(Track(listOf(Clip(clip), Clip(clip, effects = listOfNotNull(look))))),
    fill = Fill.Solid(BLACK_ARGB),
    audio = AudioSpec.Remove,
  )
}

// The slot the clip after another opens on. A band of period 0.8 seconds counts from there, so it is at the top on the
// clip's first frame and a quarter of the way down a quarter period on.
private const val OPENING_SLOT = 12L
private const val MID = 0.5f
private const val MIDDLE = 0.5

// Where the clip after another opens on the composition, which a band and a settle on the clip count from.
private val OPENS = outputSlotTime(OPENING_SLOT, 30)
private val ANCHORED_LATE = Vhs(tracking = Tracking.Rolling(0.8.seconds, height = 0.3f, snow = MID))

// The same band set to be at the top six frames after the clip's first, which is a fifth of a second.
private const val ANCHOR_FRAMES = 6L
private val ANCHORED_AFTER =
  Vhs(tracking = Tracking.Rolling(0.8.seconds, height = 0.3f, snow = MID, at = 0.2.seconds))

// A band tall enough to reach the rows a read samples, at the top on the clip's first frame, that falls to nothing over
// six frames. A read at the start, the middle and the end of that, and one after it.
private val SETTLE_SLOTS = listOf(OPENING_SLOT, OPENING_SLOT + 3, OPENING_SLOT + 6, OPENING_SLOT + 9)
private val SETTLING =
  Vhs(
    tracking =
      Tracking.Rolling(
        0.8.seconds,
        height = 0.5f,
        snow = MID,
        settle = TrackingSettle(0.2.seconds, Easing.Linear),
      ),
  )

// A look that opens on the clip's second slot at full strength and settles to 0.3 over six frames, read before it, on
// its first frame, in its middle, on its last and after it.
private val LOCKING = Vhs(intensity = 0.3f, settle = TapeSettle(1f, 0.2.seconds, Easing.Linear))
private val LOCK_SLOTS = listOf(OPENING_SLOT, OPENING_SLOT + 3, OPENING_SLOT + 6, OPENING_SLOT + 9)
