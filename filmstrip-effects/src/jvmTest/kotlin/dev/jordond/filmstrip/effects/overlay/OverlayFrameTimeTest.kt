package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.WINDOW_CLOCK
import dev.jordond.filmstrip.effects.FRAME
import dev.jordond.filmstrip.effects.attributes
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.OrientedPictures
import dev.jordond.filmstrip.test.evaluateFrameTime
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The frame time expressions an animated overlay is drawn by, read back frame by frame the way ffmpeg reads them.
 */
class OverlayFrameTimeTest {
  // Every index of the series is read, so a run's first frame, its last and the ones between are all covered.
  @Test
  fun `a series reads back its own value on every frame`() {
    val values = listOf(5, 5, 5, -12, -12, 40, 7, 7, 7, 7, 0, 3, 3)
    val runs = values.mapIndexed { index, value -> sampledAt(index + FIRST, value) }.runsOf { it.drawn.x.toString() }

    runs.runs shouldHaveSize RUN_COUNT
    values.forEachIndexed { index, value ->
      withClue("frame ${index + FIRST}") { indexAt(runs, index + FIRST) shouldBe value.toDouble() }
    }
    withClue("ahead of the first frame") { indexAt(runs, 0) shouldBe values.first().toDouble() }
    withClue("past the last frame") { indexAt(runs, FIRST + values.size + 1) shouldBe values.last().toDouble() }
  }

  @Test
  fun `a series holding one value is written as that value`() {
    listOf(1, 4).forEach { length ->
      val runs = List(length) { sampledAt(it, -3) }.runsOf { it.drawn.x.toString() }

      runs.held shouldBe true
      runs.on(branchFrameClock(RATE)) shouldBe "-3"
    }
  }

  @Test
  fun `the merge clock adds back the slot a preview window opens on`() {
    val runs = List(SLOTS) { sampledAt(it, it) }.runsOf { it.drawn.x.toString() }
    val written = runs.on(mergeFrameClock(RATE))

    listOf(0, 1, 13, 29).forEach { window ->
      val opened = written.replace(WINDOW_CLOCK, (window.toDouble() / RATE).toString())
      (0 until SLOTS - window).forEach { frame ->
        withClue("frame $frame of a window opened on slot $window") {
          evaluateFrameTime(opened, frame * (1.0 / RATE)) shouldBe (window + frame).toDouble()
        }
      }
    }
  }

  // A slide that settles part way through the span, so the middle of the ramp, both of its ends and the value it holds
  // after it are all read, against what the shared sampler answers on each frame.
  @Test
  fun `every frame of a ramp reads what the shared sampler answers for it`() {
    val spec = ImageOverlay(PICTURE, Corner.BottomEnd, animation = slideIn(OverlayOffset(SLIDE, -SLIDE), RAMP))

    assertAgreesWithSampler(spec, sdr())
  }

  // A value that jumps every frame, so a frame read with its neighbour's value cannot pass.
  @Test
  fun `every frame of a jumpy animation reads what the shared sampler answers for it`() {
    val jumpy =
      OverlayAnimation { time ->
        val frame = (time.elapsed.inWholeMicroseconds * RATE / MICROS).toInt()
        OverlayFrame(
          opacity = (frame * OPACITY_STEP % OPACITY_STEPS) / OPACITY_STEPS.toFloat(),
          offset = OverlayOffset((frame * X_STEP % X_STEPS) / STEP_SCALE, (frame * Y_STEP % Y_STEPS) / STEP_SCALE),
          scale = 1f + (frame * SCALE_STEP % SCALE_STEPS) / STEP_SCALE,
        )
      }
    val spec = ImageOverlay(PICTURE, Corner.TopStart, opacity = AUTHORED, animation = jumpy)

    assertAgreesWithSampler(spec, sdr())
    assertAgreesWithSampler(spec, sdr(span = TimeRange.of(LATE_START, LATE_START + SPAN_LENGTH)))
  }

  @Test
  fun `a held value carries no clock`() {
    val spec = ImageOverlay(PICTURE, Corner.TopStart, animation = OverlayAnimation { OverlayFrame(opacity = HALF) })
    val frames = sampled(spec, sdr())

    frames.runsOf { opacityValue(it.frame) }.on(branchPlaneClock(RATE)) shouldNotContain "st("
    frames.runsOf { it.drawn.x.toString() }.on(mergeFrameClock(RATE)) shouldNotContain WINDOW_CLOCK
  }

  private fun assertAgreesWithSampler(
    spec: ImageOverlay,
    attributes: Attributes,
  ) {
    val frames = sampled(spec, attributes)
    val drawnRange = spec.drawnRangeWithin(attributes.span)
    val placement = spec.placedOn(FRAME, OrientedPictures.SIZE)
    val x = frames.runsOf { it.drawn.x.toString() }.on(mergeFrameClock(RATE)).replace(WINDOW_CLOCK, "0")
    val y = frames.runsOf { it.drawn.y.toString() }.on(mergeFrameClock(RATE)).replace(WINDOW_CLOCK, "0")
    val width = frames.runsOf { it.drawn.width.toString() }.on(branchFrameClock(RATE))
    val height = frames.runsOf { it.drawn.height.toString() }.on(branchFrameClock(RATE))
    val opacity = frames.runsOf { opacityValue(it.frame) }.on(branchPlaneClock(RATE))
    val first = outputSlotOf(attributes.span.start, RATE)

    val end = checkNotNull(attributes.span.endExclusive)
    val inSpan =
      generateSequence(0L) { it + 1 }
        .takeWhile { outputSlotTime(first + it, RATE) < end }
        .filter { outputSlotTime(first + it, RATE) >= attributes.span.start }
        .toList()

    withClue("the span reaches too few frames to read") { (inSpan.size >= SLOTS - 1) shouldBe true }
    inSpan.forEach { index ->
      val time = outputSlotTime(first + index, RATE)
      val frame = spec.frameWithin(drawnRange, time).shouldNotBeNull()
      val drawn = drawnRect(placement.animatedBy(frame), FRAME)
      val t = index * (1.0 / RATE)

      withClue("frame $index at $time") {
        evaluateFrameTime(x, t) shouldBe drawn.x.toDouble()
        evaluateFrameTime(y, t) shouldBe drawn.y.toDouble()
        evaluateFrameTime(width, t) shouldBe drawn.width.toDouble()
        evaluateFrameTime(height, t) shouldBe drawn.height.toDouble()
        evaluateFrameTime(opacity, t) shouldBe opacityValue(frame).toDouble()
      }
    }
  }

  private fun sampled(
    spec: ImageOverlay,
    attributes: Attributes,
  ): List<SampledFrame> =
    PlacedPicture(spec, GraphPicture.Opened(PICTURE), spec.placedOn(FRAME, OrientedPictures.SIZE), spec.opacity)
      .sampledFrames(attributes, spec.drawnRangeWithin(attributes.span))
      .shouldBeInstanceOf<Sampling.Sampled>()
      .frames

  private fun indexAt(
    runs: FrameRuns,
    index: Int,
  ): Double = evaluateFrameTime(runs.on(branchFrameClock(RATE)), index * (1.0 / RATE))

  // A frame whose left edge carries value, so a series of plain numbers reads through runsOf.
  private fun sampledAt(
    index: Int,
    value: Int,
  ): SampledFrame = SampledFrame(index, OverlayFrame.Identity, PixelRect(value, 0, 1, 1))

  private fun sdr(span: TimeRange = TimeRange.of(Duration.ZERO, SPAN_LENGTH)): Attributes {
    val base = attributes(null)
    return Attributes(
      inputSize = FRAME,
      outputSize = base.outputSize,
      layoutSize = base.layoutSize,
      colorSpace = base.colorSpace,
      hdrTransfer = null,
      frameRate = RATE.toFloat(),
      span = span,
    )
  }

  private companion object {
    val PICTURE = ImageSource.ofBytes(OrientedPictures.png())
    const val RATE = 30
    const val MICROS = 1_000_000L
    const val FIRST = 3
    const val RUN_COUNT = 6
    const val SLOTS = 60
    const val SLIDE = 0.3f
    const val HALF = 0.5f
    const val AUTHORED = 0.8f
    val RAMP = 900.milliseconds
    val SPAN_LENGTH = 2.seconds

    // Off the frame grid, so the span's first slot is not at its start.
    val LATE_START = 1010.milliseconds

    const val OPACITY_STEP = 13
    const val OPACITY_STEPS = 21
    const val X_STEP = 37
    const val X_STEPS = 50
    const val Y_STEP = 53
    const val Y_STEPS = 30
    const val SCALE_STEP = 11
    const val SCALE_STEPS = 40
    const val STEP_SCALE = 100f
  }
}
