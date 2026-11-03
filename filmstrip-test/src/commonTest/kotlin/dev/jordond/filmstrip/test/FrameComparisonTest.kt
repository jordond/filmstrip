package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The comparison the parity harness rests on.
 *
 * Comparing black against black passes trivially, so the negative controls matter as much as the
 * positive ones.
 */
class FrameComparisonTest {
  @Test
  fun identicalFramesAreIdentical() {
    val frame = noise(SIZE)
    val comparison = compareFrames(frame, frame)

    comparison.psnrDb.isInfinite() shouldBe true
    comparison.meanAbsoluteDifference shouldBe 0.0
    withClue("SSIM was ${comparison.ssim}") { (comparison.ssim > 0.999) shouldBe true }
  }

  @Test
  fun aOneChannelUnitShiftIsStillTheSameFrame() {
    val frame = noise(SIZE)
    val shifted = TestFrame(ByteArray(frame.pixels.size) { (frame.pixels[it] + 1).toByte() }, SIZE)

    assertFramesSimilar(frame, shifted)
  }

  @Test
  fun anInvertedFrameIsNotTheSameFrame() {
    val frame = noise(SIZE)
    val inverted = TestFrame(ByteArray(frame.pixels.size) { (255 - frame.pixels[it].toInt()).toByte() }, SIZE)

    assertFramesDiffer(frame, inverted)
    assertFailsWith<AssertionError> { assertFramesSimilar(frame, inverted) }
  }

  @Test
  fun blackAgainstBlackPassesAndIsWhyTheNegativeControlExists() {
    val black = TestFrame(ByteArray(SIZE.width * SIZE.height * 4), SIZE)

    // Two empty frames agree perfectly, which is exactly how a broken render can look like a
    // flawless result. Scoring a comparison is only meaningful when the frames carry content.
    assertFramesSimilar(black, black)
    assertFailsWith<AssertionError> { assertFramesDiffer(black, black) }
  }

  @Test
  fun localisedDamageIsCaughtRatherThanAveragedAway() {
    val frame = noise(SIZE)
    val damaged = TestFrame(frame.pixels.copyOf(), SIZE)
    // Wreck one 8x8 window out of 1024 and leave the rest untouched. A whole-frame average would
    // shrug that off. Windowed structural similarity is what notices.
    for (y in 0 until 8) {
      for (x in 0 until 8) {
        val base = (y * SIZE.width + x) * 4
        for (channel in 0 until 4) damaged.pixels[base + channel] = 0
      }
    }

    val comparison = compareFrames(frame, damaged)
    withClue("SSIM was ${comparison.ssim}") { (comparison.ssim < 1.0) shouldBe true }
    (comparison.meanAbsoluteDifference > 0.0) shouldBe true
  }

  @Test
  fun mismatchedSizesAreRefusedRatherThanCompared() {
    assertFailsWith<IllegalArgumentException> {
      compareFrames(noise(SIZE), noise(Size(128, 128)))
    }
  }

  @Test
  fun aBufferOfTheWrongLengthIsRefused() {
    assertFailsWith<IllegalArgumentException> { TestFrame(ByteArray(10), SIZE) }
  }

  /**
   * Deterministic pseudo-noise, so a run is reproducible and a window has real structure in it.
   */
  private fun noise(size: Size): TestFrame {
    var state = 0x2545F491u
    val pixels = ByteArray(size.width * size.height * 4)
    for (index in pixels.indices) {
      state = state * 1664525u + 1013904223u
      pixels[index] = ((state shr 16).toInt() and 0x7F).toByte()
    }
    return TestFrame(pixels, size)
  }

  private companion object {
    val SIZE = Size(256, 256)
  }
}
