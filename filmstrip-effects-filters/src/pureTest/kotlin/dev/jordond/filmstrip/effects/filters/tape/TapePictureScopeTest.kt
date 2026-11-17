package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.TrackingSettle
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Where a look counts its settle and its rolling band from, which is the first frame of the scope it is in, and how
 * the band's colour follows the level of a look that settles.
 */
class TapePictureScopeTest {
  private val settle = TrackingSettle(SETTLE)
  private val rolling = Tracking.Rolling(PERIOD, settle = settle)

  // Slots that open at 10 s: whole, trimmed to a short run, and the shorter slot a clip at speed 2 takes.
  private val lateSlots =
    listOf(
      TimeRange(10.seconds, 40.seconds),
      TimeRange(10.seconds, 14.seconds),
      TimeRange(10.seconds, 25.seconds),
      TimeRange(25.seconds, 55.seconds),
    )

  @Test
  fun `a rolling band at the default anchor is at the top on the first frame of a clip whatever its slot`() {
    val look = Vhs(tracking = rolling)

    lateSlots.forEach { slot ->
      withClue("slot $slot") {
        val first = frameAt(look, slot, Duration.ZERO)
        val band = TapePictureSignal.trackingBand(look.seed, first.tick, first.params)

        band.centre shouldBe 0.0
        band.strength shouldBe 1.0
        TapePictureSignal.bandSettle(first.tick, first.params) shouldBe 1.0
      }
    }
  }

  @Test
  fun `a settling band is on the clip for its whole settle and gone after it wherever the clip sits`() {
    val look = Vhs(tracking = rolling)

    lateSlots.forEach { slot ->
      withClue("slot $slot") {
        val inside = frameAt(look, slot, SETTLE / 2)
        val after = frameAt(look, slot, SETTLE + 1.seconds)

        TapePictureSignal.bandSettle(inside.tick, inside.params) shouldBeGreaterThan 0.0
        TapePictureSignal.bandSettle(inside.tick, inside.params) shouldBeLessThan 1.0
        TapePictureSignal.bandSettle(after.tick, after.params) shouldBe 0.0
      }
    }
  }

  @Test
  fun `a clip draws the band the way the same clip at the start of a composition does`() {
    val look = Vhs(tracking = rolling)

    lateSlots.forEach { slot ->
      listOf(0.seconds, 500.milliseconds, 1.seconds, 2.seconds, 3.seconds).forEach { into ->
        val late = frameAt(look, slot, into)
        val early = frameAt(look, TimeRange(Duration.ZERO), into)

        // The two ticks of a clip's start and of its frame round apart by one tick at most, a period's 1/360th.
        withClue("slot $slot, $into in") {
          TapePictureSignal.trackingBand(look.seed, late.tick, late.params).centre shouldBe
            (TapePictureSignal.trackingBand(look.seed, early.tick, early.params).centre plusOrMinus 6e-3)
          TapePictureSignal.bandSettle(late.tick, late.params) shouldBe
            (TapePictureSignal.bandSettle(early.tick, early.params) plusOrMinus 2e-2)
        }
      }
    }
  }

  @Test
  fun `a band given an anchor counts it from the first frame of the scope`() {
    val look = Vhs(tracking = Tracking.Rolling(PERIOD, at = 2.seconds))
    val slot = TimeRange(10.seconds, 40.seconds)
    val onTheAnchor = frameAt(look, slot, 2.seconds)

    TapePictureSignal.trackingBand(look.seed, onTheAnchor.tick, onTheAnchor.params).centre shouldBe 0.0
    onTheAnchor.params.trackingAnchorTicks shouldBe TapePictureSignal.tickAt(12.0)
  }

  @Test
  fun `a band on the composition is unchanged by counting from its start`() {
    val look = Vhs(tracking = Tracking.Rolling(PERIOD, at = 2.seconds, settle = settle))

    TapePictureParams.of(look, Duration.ZERO).trackingAnchorTicks shouldBe TapePictureSignal.tickAt(2.0)
    TapePictureFrame.of(look, 5.0, TimeRange(Duration.ZERO), null).params.trackingAnchorTicks shouldBe
      TapePictureSignal.tickAt(2.0)
  }

  @Test
  fun `each lap of a looping track anchors its clips anew`() {
    val look = Vhs(tracking = rolling)
    val lapOne = TimeRange(10.seconds, 18.seconds)
    val lapTwo = TimeRange(18.seconds, 26.seconds)
    val lapThree = TimeRange(26.seconds, 34.seconds)

    listOf(lapOne, lapTwo, lapThree).forEach { lap ->
      val first = frameAt(look, lap, Duration.ZERO)
      val middle = frameAt(look, lap, SETTLE / 2)

      withClue("lap $lap") {
        TapePictureSignal.trackingBand(look.seed, first.tick, first.params).centre shouldBe 0.0
        TapePictureSignal.bandSettle(first.tick, first.params) shouldBe 1.0
        TapePictureSignal.bandSettle(middle.tick, middle.params) shouldBeLessThan 1.0
      }
    }
  }

  @Test
  fun `a look on a track settles again on every clip and on every lap of a looping track`() {
    val look = Vhs(intensity = 0.8f, settle = TapeSettle(0f, SETTLE, Easing.Linear))
    val slots =
      listOf(TimeRange(0.seconds, 8.seconds), TimeRange(8.seconds, 13.seconds), TimeRange(13.seconds, 21.seconds))

    slots.forEach { slot ->
      val first = TapePictureFrame.of(look, slot.start.toDouble(DurationUnit.SECONDS), slot, null)
      val after = TapePictureFrame.of(look, slot.start.toDouble(DurationUnit.SECONDS) + 5.0, slot, null)

      withClue("slot $slot") {
        first.params.lumaSigma shouldBe 0.0
        after.params.lumaSigma shouldBe TapePictureParams.of(look).lumaSigma
      }
    }
  }

  @Test
  fun `a wall preview draws a settling band as it is once it has settled and gone`() {
    val look = Vhs(tracking = rolling, clock = TapeClock.Wall)
    val timed = TapePictureFrame.of(look, 1.0, TimeRange(Duration.ZERO), null)
    val wall = TapePictureFrame.of(look, 1.0, TimeRange(Duration.ZERO), PreviewClock { 1.seconds })

    (0 until VIRTUAL_ROWS).maxOf {
      TapePictureSignal.trackingWeight(look.seed, timed.tick, it, timed.params)
    } shouldBeGreaterThan
      0.0
    (0 until VIRTUAL_ROWS).forEach { row ->
      TapePictureSignal.trackingWeight(look.seed, wall.tick, row, wall.params) shouldBe 0.0
    }
    wall.params.trackingNoise shouldBe 0.0
  }

  @Test
  fun `a wall preview keeps a band that does not settle rolling from tick zero`() {
    val look = Vhs(tracking = Tracking.Rolling(PERIOD), clock = TapeClock.Wall)
    val wall = TapePictureFrame.of(look, 1.0, TimeRange(10.seconds), PreviewClock { 1.seconds })

    wall.params.trackingAnchorTicks shouldBe 0
    TapePictureSignal.trackingBand(look.seed, wall.tick, wall.params).strength shouldBe 1.0
  }

  @Test
  fun `a constant look keeps the whole colour loss of its band`() {
    Vhs(intensity = 0.7f, tracking = Tracking.Rolling()).let {
      TapePictureParams.of(it).trackingChromaLoss shouldBe TRACK_CHROMA_LOSS
    }
    Vhs(intensity = 0.3f, tracking = Tracking.Events()).let {
      TapePictureParams.of(it).trackingChromaLoss shouldBe TRACK_CHROMA_LOSS
    }
  }

  @Test
  fun `the colour a band takes never steps by more than a tick of the settle from nothing or to nothing or across`() {
    val cases =
      listOf(0f to 0.8f, 0.8f to 0f, 1f to 0.5f, 0.5f to 1f, 0f to 1f, 1f to 0f, 0.2f to 0.9f, 0.9f to 0.2f)

    Easing.entries.forEach { easing ->
      cases.forEach { (from, to) ->
        listOf(Tracking.Rolling(PERIOD), Tracking.Events(chance = 1f)).forEach { tracking ->
          val look = Vhs(intensity = to, tracking = tracking, settle = TapeSettle(from, SETTLE, easing))
          val slot = TimeRange(10.seconds, 40.seconds)
          val ramp = TapePictureRamp.of(look, slot)!!
          val losses =
            (ramp.startTick - 2..ramp.endTick + 2).map { tick ->
              TapePictureFrame.of(look, tick / TICK_RATE, slot, null).params.trackingChromaLoss
            }
          val steps = losses.zipWithNext { a, b -> abs(b - a) }

          withClue("$easing $from to $to on $tracking") {
            // The loss is half at most and the level moves by at most a tenth of its range on any tick, which the
            // easing's steepest slope of 2 over the settle's 240 ticks keeps well under.
            steps.max() shouldBeLessThan 0.01
          }
        }
      }
    }
  }

  @Test
  fun `the colour a band takes at the ends of a settle is the constant look's own`() {
    val up = Vhs(intensity = 0.8f, tracking = Tracking.Rolling(PERIOD), settle = TapeSettle(0f, SETTLE))
    val down = Vhs(intensity = 0f, tracking = Tracking.Rolling(PERIOD), settle = TapeSettle(0.8f, SETTLE))
    val slot = TimeRange(Duration.ZERO)
    val end = TapePictureRamp.of(up, slot)!!.endTick

    TapePictureFrame.of(up, 0.0, slot, null).params.trackingChromaLoss shouldBe 0.0
    TapePictureFrame.of(up, (end + 1) / TICK_RATE, slot, null).params.trackingChromaLoss shouldBe TRACK_CHROMA_LOSS
    TapePictureFrame.of(down, (end - 1) / TICK_RATE, slot, null).params.trackingChromaLoss shouldBeLessThan 0.01
    TapePictureFrame.of(down, (end + 1) / TICK_RATE, slot, null).params.trackingChromaLoss shouldBe 0.0
  }

  @Test
  fun `from above its intensity the band takes the whole colour loss the whole way down`() {
    val look = Vhs(intensity = 0.5f, tracking = Tracking.Rolling(PERIOD), settle = TapeSettle(1f, SETTLE))
    val slot = TimeRange(Duration.ZERO)
    val ramp = TapePictureRamp.of(look, slot)!!

    (ramp.startTick..ramp.endTick).forEach { tick ->
      TapePictureFrame.of(look, tick / TICK_RATE, slot, null).params.trackingChromaLoss shouldBe TRACK_CHROMA_LOSS
    }
  }

  private fun frameAt(
    look: Vhs,
    slot: TimeRange,
    into: Duration,
  ) = TapePictureFrame.of(look, (slot.start + into).toDouble(DurationUnit.SECONDS), slot, null)

  private companion object {
    val PERIOD = 6.seconds
    val SETTLE = 4.seconds
  }
}
