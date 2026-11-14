package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalFilmstripApi::class)
class PreviewClockTest {
  @Test
  fun `an effect holds still with the picture unless it says otherwise`() {
    Plain.redrawsWhileHeld shouldBe false
  }

  @Test
  fun `attributes built without a preview clock carry none`() {
    attributes().previewClock.shouldBeNull()
  }

  @Test
  fun `attributes hand a resolver the clock they were built with`() {
    var now = 1370.milliseconds
    val carried = attributes(PreviewClock { now }).previewClock

    carried?.now() shouldBe 1370.milliseconds
    now = 2.seconds
    carried?.now() shouldBe 2.seconds
  }

  private fun attributes(clock: PreviewClock? = null): Attributes {
    val frame = Size(1920, 1080)
    return Attributes(
      inputSize = frame,
      outputSize = frame,
      layoutSize = frame,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = 30f,
      span = TimeRange(Duration.ZERO, 4.seconds),
      previewClock = clock,
    )
  }

  private object Plain : EffectSpec {
    override val id: EffectId = EffectId("test.plain")
  }
}
