package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsBuilder
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Which time a [Vhs] draws its tape at, by its [TapeClock] and whether a preview clock is there.
 */
class TapePictureClockTest {
  private val wall = Vhs(clock = TapeClock.Wall)
  private val composition = Vhs()

  @Test
  fun `a composition tape draws at the frame's time whatever the preview clock reads`() {
    READINGS.forEach { reading ->
      FRAME_TIMES.forEach { frame ->
        TapePictureSignal.tapeSeconds(composition, frame, PreviewClock { reading }) shouldBe frame
      }
    }
  }

  @Test
  fun `a wall tape draws at the frame's time where there is no preview clock`() {
    FRAME_TIMES.forEach { frame -> TapePictureSignal.tapeSeconds(wall, frame, null) shouldBe frame }
  }

  @Test
  fun `a wall tape draws at the preview clock's reading whatever the frame's time`() {
    READINGS.forEach { reading ->
      FRAME_TIMES.forEach { frame ->
        TapePictureSignal.tapeSeconds(wall, frame, PreviewClock { reading }) shouldBe
          reading.toDouble(DurationUnit.SECONDS)
      }
    }
  }

  @Test
  fun `a held frame draws a different tape once the preview clock moves a tick on`() {
    var now = 1370.milliseconds
    val clock = PreviewClock { now }
    val held = 2.5

    val first = TapePictureShader.uniforms(wall, TapePictureFrame.of(wall, held, WHOLE, clock))
    now += ONE_TICK
    val second = TapePictureShader.uniforms(wall, TapePictureFrame.of(wall, held, WHOLE, clock))

    second.getValue("uTick") shouldBe first.getValue("uTick") + 1f
    TapePictureShader.uniforms(composition, TapePictureFrame.of(composition, held, WHOLE, clock)) shouldBe
      TapePictureShader.uniforms(composition, TapePictureFrame.of(composition, held, WHOLE, null))
  }

  @Test
  fun `a wall tape draws its rolling band from tick zero whatever it is anchored to`() {
    val anchored = Vhs(tracking = Tracking.Rolling(at = 5.seconds), clock = TapeClock.Wall)
    val clock = PreviewClock { 2.seconds }

    val onWall = TapePictureFrame.of(anchored, 9.0, WHOLE, clock)
    val plain = TapePictureFrame.of(Vhs(tracking = Tracking.Rolling(), clock = TapeClock.Wall), 9.0, WHOLE, clock)

    onWall.params.trackingAnchorTicks shouldBe 0
    TapePictureSignal.trackingBand(anchored.seed, onWall.tick, onWall.params).centre shouldBe
      TapePictureSignal.trackingBand(anchored.seed, plain.tick, plain.params).centre
    TapePictureFrame.of(anchored, 9.0, WHOLE, null).params.trackingAnchorTicks shouldBe
      TapePictureSignal.tickAt(5.0)
  }

  @Test
  fun `only a wall tape asks a preview to keep drawing a held frame`() {
    wall.redrawsWhileHeld shouldBe true
    composition.redrawsWhileHeld shouldBe false
  }

  @Test
  fun `the clock is part of what a tape is and the builder starts on composition time`() {
    wall shouldNotBe composition
    built { }.clock shouldBe TapeClock.Composition
    built { clock = TapeClock.Wall } shouldBe wall
  }

  private fun built(block: VhsBuilder.() -> Unit): Vhs {
    val effects = EffectsBuilder().vhs(block).build()
    effects shouldHaveSize 1
    return effects.single().shouldBeInstanceOf<Vhs>()
  }

  private companion object {
    val READINGS: List<Duration> = listOf(Duration.ZERO, 1370.milliseconds, 3600.5.seconds)
    val FRAME_TIMES: List<Double> = listOf(0.0, 1.37, 3600.5)
    val ONE_TICK: Duration = (1.0 / TICK_RATE).seconds
  }
}
