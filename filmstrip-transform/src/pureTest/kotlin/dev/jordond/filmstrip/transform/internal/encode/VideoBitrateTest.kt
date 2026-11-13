package dev.jordond.filmstrip.transform.internal.encode

import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.shouldBe
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.Test

class VideoBitrateTest {
  @Test
  fun mediumAt1080pInH264IsTheReferenceRateTimesTheMediumFactor() {
    val expected = 3_000_000.0 * 1.0 * (0.3 * exp(2.5538 * 0.5))

    resolveVideoBitrate(VideoQuality.Medium, VideoCodec.H264, FULL_HD, 30).bitsPerSecond shouldBe kilobitsUp(expected)
  }

  @Test
  fun lowAt720pInHevcScalesThePixelsAndTheCodecFactor() {
    val pixels = (1280.0 * 720.0 / (1920.0 * 1080.0)).pow(0.95)
    val expected = 3_000_000.0 * pixels * 0.6 * (0.3 * exp(2.5538 * 0.25))

    resolveVideoBitrate(VideoQuality.Low, VideoCodec.Hevc, Size(1280, 720), 30).bitsPerSecond shouldBe
      kilobitsUp(expected)
  }

  @Test
  fun sixtyFramesASecondDoublesThirty() {
    val thirty = resolveVideoBitrate(VideoQuality.Medium, VideoCodec.H264, FULL_HD, 30).bitsPerSecond
    val sixty = resolveVideoBitrate(VideoQuality.Medium, VideoCodec.H264, FULL_HD, 60).bitsPerSecond

    // Both round up to a whole kilobit once, so doubling the rounded figure can land one kilobit over.
    sixty.shouldBeBetween(thirty * 2 - 1_000, thirty * 2)
    sixty shouldBe kilobitsUp(3_000_000.0 * (0.3 * exp(2.5538 * 0.5)) * 2.0)
  }

  @Test
  fun fortyFiveFramesASecondScalesByOneAndAHalf() {
    val expected = 3_000_000.0 * (0.3 * exp(2.5538 * 0.75)) * 1.5

    resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, FULL_HD, 45).bitsPerSecond shouldBe kilobitsUp(expected)
  }

  @Test
  fun twentyFourFramesASecondMatchesThirty() {
    resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, FULL_HD, 24) shouldBe
      resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, FULL_HD, 30)
  }

  private fun kilobitsUp(bits: Double): Long = ceil(bits / 1_000.0).toLong() * 1_000L

  private companion object {
    val FULL_HD = Size(1920, 1080)
  }
}
