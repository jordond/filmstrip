package dev.jordond.filmstrip.media3.internal.encoder

import androidx.media3.transformer.VideoEncoderSettings
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VideoEncoderSettingsTest {
  @Test
  fun `the encoder is asked for the plan's bitrate`() {
    val planned = resolveVideoBitrate(VideoQuality.Medium, VideoCodec.Hevc, Size(1280, 720), 60)

    videoEncoderSettings(planned).bitrate.toLong() shouldBe planned.bitsPerSecond
  }

  // media3's VideoEncoderSettings takes the bitrate as an Int.
  @Test
  fun `a rate past media3's Int bitrate is held at Int MAX_VALUE`() {
    videoEncoderSettings(Bitrate(Long.MAX_VALUE)).bitrate shouldBe Int.MAX_VALUE
  }

  @Test
  fun `a copy leaves media3's own settings alone`() {
    videoEncoderSettings(null) shouldBe VideoEncoderSettings.DEFAULT
  }
}
