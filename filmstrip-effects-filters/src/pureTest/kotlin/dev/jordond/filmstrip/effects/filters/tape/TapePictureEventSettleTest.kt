package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * How an event band follows the level of a look that settles: it fades in and out by how far its roll is under the
 * chance, so it never switches on or off in a step, and a look that does not settle keeps every band it ever had.
 */
class TapePictureEventSettleTest {
  private val slot = TimeRange(Duration10, 60.seconds)

  @Test
  fun `a band never steps by more than a fifth of its strength between two ticks of a settle`() {
    val pairs = listOf(1f to 0.35f, 0.35f to 1f, 0f to 1f, 1f to 0f, 0.8f to 0.5f)

    Easing.entries.forEach { easing ->
      pairs.forEach { (from, to) ->
        var worst = 0.0
        (0 until SEEDS).forEach { seed ->
          val look =
            Vhs(
              intensity = to,
              seed = seed,
              tracking = Tracking.Events(chance = 0.6f),
              settle = TapeSettle(from, 4.seconds, easing),
            )
          val ramp = TapePictureRamp.of(look, slot)!!
          var before: Double? = null
          for (tick in ramp.startTick - 2..ramp.endTick + 2) {
            val params = look.pictureParams(ramp.levelAt(tick), slot.start)
            val strength = TapePictureSignal.trackingBand(seed, tick, params).strength
            before?.let { worst = maxOf(worst, abs(strength - it)) }
            before = strength
          }
        }
        withClue("$easing from $from to $to over $SEEDS seeds") { worst shouldBeLessThan STEP }
      }
    }
  }

  @Test
  fun `an ease in steps a band by up to 16 over the ticks of the settle and a short one can switch it in a tick`() {
    listOf(0.5.seconds, 1.seconds, 0.1.seconds).forEach { over ->
      var worst = 0.0
      var ticks = 0.0
      (0 until SEEDS).forEach { seed ->
        val look =
          Vhs(
            seed = seed,
            tracking = Tracking.Events(chance = 1f),
            settle = TapeSettle(0f, over, Easing.EaseIn),
          )
        val ramp = TapePictureRamp.of(look, slot)!!
        ticks = ramp.overTicks
        var before: Double? = null
        for (tick in ramp.startTick - 2..ramp.endTick + 2) {
          val params = look.pictureParams(ramp.levelAt(tick), slot.start)
          // A band's own envelope moves little over a tick, so the steps here are the fade's.
          val strength = TapePictureSignal.trackingBand(seed, tick, params).strength
          before?.let { worst = maxOf(worst, abs(strength - it)) }
          before = strength
        }
      }
      val bound = 16.0 / ticks
      withClue("$over over $ticks ticks, worst step $worst, bound $bound") {
        worst shouldBeLessThan minOf(1.0, bound) + SLACK
        worst shouldBeGreaterThan minOf(1.0, bound) / 2
      }
    }
  }

  @Test
  fun `a look that does not settle keeps every band it ever had at full strength`() {
    val look = Vhs(tracking = Tracking.Events(chance = 0.6f))
    val params = TapePictureParams.of(look)

    params.trackingChanceFrom shouldBe params.trackingChance
    params.trackingChanceTo shouldBe params.trackingChance
    (0 until SEEDS).forEach { seed ->
      (0 until WINDOWS_TICKS step 7).forEach { tick ->
        val strength = TapePictureSignal.trackingBand(seed, tick, params).strength
        val phase = (tick % TapePictureSignal.trackWindowTicks).toDouble() / TapePictureSignal.trackWindowTicks

        if (strength > 0.0) strength shouldBe (sin(PI * phase) plusOrMinus 1e-12)
      }
    }
  }

  @Test
  fun `a settle that starts where it ends has no fade`() {
    val look = Vhs(intensity = 0.4f, settle = TapeSettle(0.4f, 4.seconds))

    val params = TapePictureParams.of(look)

    params.trackingChanceFrom shouldBe params.trackingChance
    params.trackingChanceTo shouldBe params.trackingChance
  }

  @Test
  fun `a settle starts on the plain look's bands and ends on the plain look's at its strength`() {
    listOf(0.2f, 0.35f, 0.5f, 1f).forEach { intensity ->
      listOf(0f, 0.6f, 1f).filter { it != intensity }.forEach { from ->
        val settling =
          Vhs(intensity = intensity, tracking = Tracking.Events(chance = 0.6f), settle = TapeSettle(from, 4.seconds))
        val ramp = TapePictureRamp.of(settling, slot)!!
        val atEnd = settling.pictureParams(ramp.levelAt(ramp.endTick), slot.start)
        val atStart = settling.pictureParams(ramp.levelAt(ramp.startTick), slot.start)
        val settledPlain = TapePictureParams.of(Vhs(intensity = intensity, tracking = Tracking.Events(chance = 0.6f)))
        val startPlain = TapePictureParams.of(Vhs(intensity = from, tracking = Tracking.Events(chance = 0.6f)))

        withClue("from $from to $intensity") {
          (0 until SEEDS).forEach { seed ->
            (0 until WINDOWS_TICKS step 5).forEach { tick ->
              same(
                TapePictureSignal.trackingBand(seed, tick, atEnd),
                TapePictureSignal.trackingBand(seed, tick, settledPlain),
              )
              same(
                TapePictureSignal.trackingBand(seed, tick, atStart),
                TapePictureSignal.trackingBand(seed, tick, startPlain),
              )
            }
          }
        }
      }
    }
  }

  private fun same(
    band: TrackingBand,
    plain: TrackingBand,
  ) {
    band.strength shouldBe (plain.strength plusOrMinus 1e-12)
    if (plain.strength > 0.0) band.centre shouldBe (plain.centre plusOrMinus 1e-12)
  }

  private companion object {
    val Duration10 = 10.seconds
    const val SEEDS = 400
    const val STEP = 0.2
    const val SLACK = 0.05
    const val WINDOWS_TICKS = 2400
  }
}
