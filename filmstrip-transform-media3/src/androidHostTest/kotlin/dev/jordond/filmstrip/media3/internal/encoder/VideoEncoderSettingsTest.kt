package dev.jordond.filmstrip.media3.internal.encoder

import androidx.media3.transformer.VideoEncoderSettings
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.encode.keyFrameIntervalOf
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VideoEncoderSettingsTest {
  @Test
  fun `the encoder is asked for the plan's bitrate`() {
    val planned = resolveVideoBitrate(VideoQuality.Medium, VideoCodec.Hevc, Size(1280, 720), 60)

    videoEncoderSettings(planned, null).bitrate.toLong() shouldBe planned.bitsPerSecond
  }

  // media3's VideoEncoderSettings takes the bitrate as an Int.
  @Test
  fun `a rate past media3's Int bitrate is held at Int MAX_VALUE`() {
    videoEncoderSettings(Bitrate(Long.MAX_VALUE), null).bitrate shouldBe Int.MAX_VALUE
  }

  @Test
  fun `a copy leaves media3's own settings alone`() {
    videoEncoderSettings(null, null) shouldBe VideoEncoderSettings.DEFAULT
  }

  @Test
  fun `the encoder is asked for the plan's key frame spacing in seconds of its rate`() {
    for ((interval, rate) in listOf(1.seconds to 30, 500.milliseconds to 25, 400.milliseconds to 24)) {
      val frames = keyFrameIntervalOf(ExportSpec(keyFrameInterval = interval), rate)
      val output = OutputFormat(Size(1280, 720), VideoCodec.H264, AudioCodec.None, null, rate, null, frames)

      val seconds = keyFrameSecondsOf(output).shouldNotBeNull()

      (seconds * rate).roundToInt() shouldBe frames
      videoEncoderSettings(null, seconds).iFrameIntervalSeconds shouldBe seconds
    }
  }

  @Test
  fun `a plan with no key frame spacing asks for none`() {
    keyFrameSecondsOf(OutputFormat(Size(1280, 720), VideoCodec.H264, AudioCodec.None, null, 30, null)).shouldBeNull()
  }
}
