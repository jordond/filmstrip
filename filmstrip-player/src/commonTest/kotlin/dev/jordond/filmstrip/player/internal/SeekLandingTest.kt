package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SeekLandingTest {
  private val composition = composition(RATE, LENGTH)
  private val lastSlot = outputSlotOf(LENGTH, RATE) - 1

  @Test
  fun `a seek between two frames lands on the slot it rounds to`() {
    composition.seekLandingOf(ROUNDS_DOWN) shouldBe outputSlotTime(13, RATE)
    composition.seekLandingOf(ROUNDS_UP) shouldBe outputSlotTime(14, RATE)
  }

  @Test
  fun `a seek at or before the start lands on the first frame`() {
    composition.seekLandingOf(Duration.ZERO) shouldBe Duration.ZERO
    composition.seekLandingOf((-40).milliseconds) shouldBe Duration.ZERO
  }

  @Test
  fun `a seek at or past the end lands on the last slot the composition covers`() {
    composition.seekLandingOf(LENGTH) shouldBe outputSlotTime(lastSlot, RATE)
    composition.seekLandingOf(10.seconds) shouldBe outputSlotTime(lastSlot, RATE)
  }

  // At 24fps a slot is not a whole number of milliseconds, so a landing that went through milliseconds anywhere would
  // come back a fraction off the slot it started on.
  @Test
  fun `a slot time that is not whole milliseconds lands on itself`() {
    val film = composition(FILM_RATE, LENGTH)
    outputSlotTime(1, FILM_RATE).inWholeMicroseconds % 1000 shouldNotBe 0

    for (slot in 0 until outputSlotOf(LENGTH, FILM_RATE)) {
      film.seekLandingOf(outputSlotTime(slot, FILM_RATE)) shouldBe outputSlotTime(slot, FILM_RATE)
    }
  }

  @Test
  fun `a frame step from between two frames lands on the output grid`() {
    val slot = outputSlotOf(ROUNDS_UP, RATE)

    composition.frameStepLanding(ROUNDS_UP, 1) shouldBe outputSlotTime(slot + 1, RATE)
    composition.frameStepLanding(ROUNDS_UP, -1) shouldBe outputSlotTime(slot - 1, RATE)
  }

  // Counted in slots, so a step back to the first frame lands on zero itself rather than a fraction short of it.
  @Test
  fun `a frame step back to the first frame lands on zero and one further is refused`() {
    val back = outputSlotOf(ROUNDS_UP, RATE).toInt()

    composition.frameStepLanding(ROUNDS_UP, -back) shouldBe Duration.ZERO
    composition.frameStepLanding(ROUNDS_UP, -back - 1) shouldBe null
  }

  @Test
  fun `a frame step past the last slot is refused and one back from the end lands on it`() {
    composition.frameStepLanding(outputSlotTime(lastSlot, RATE), 1) shouldBe null
    composition.frameStepLanding(LENGTH, -1) shouldBe outputSlotTime(lastSlot, RATE)
  }

  // The duration itself sits past the last slot's own time, so a step forward from there is refused too.
  @Test
  fun `a frame step forward from the composition's duration is refused`() {
    composition.frameStepLanding(LENGTH, 1) shouldBe null
  }

  // 30fps, where a frame is not a whole number of microseconds, so adding frame lengths would drift off the grid.
  @Test
  fun `a run of single frame steps lands where one step of the whole count does`() {
    val video = composition(VIDEO_RATE, LENGTH)
    val stepped = (1..STEPS).fold(Duration.ZERO) { at, _ -> video.frameStepLanding(at, 1).shouldNotBeNull() }

    stepped shouldBe outputSlotTime(STEPS.toLong(), VIDEO_RATE)
    stepped shouldBe video.frameStepLanding(Duration.ZERO, STEPS)
  }

  private companion object {
    const val RATE = 25
    const val FILM_RATE = 24
    const val VIDEO_RATE = 30
    const val STEPS = 30
    val LENGTH: Duration = 3030.milliseconds

    // Slots 13 and 14 at 25fps are 520ms and 560ms, so these sit a quarter of a slot past one and before the other.
    val ROUNDS_DOWN: Duration = 530.milliseconds
    val ROUNDS_UP: Duration = 550.milliseconds

    val FRAME = Size(1280, 720)

    fun composition(
      frameRate: Int,
      duration: Duration,
    ): ResolvedComposition =
      ResolvedComposition(
        tracks = emptyList(),
        compositionGeometry = emptyList(),
        compositionInputSize = FRAME,
        compositionEffects = emptyList(),
        output =
          OutputFormat(
            size = FRAME,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.Aac,
            bitrate = null,
            frameRate = frameRate,
            audioFormat = null,
          ),
        layoutSize = FRAME,
        fit = Fit.Contain,
        fill = Fill.Solid(),
        duration = duration,
        hdr = ResolvedHdr.ToneMap,
        hdrTransfer = null,
        path = ExportPath.Transcode,
        audio = AudioSpec.Keep,
        adjustments = emptyList(),
      )
  }
}
