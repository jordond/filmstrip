package dev.jordond.filmstrip.export

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ExportSpecTest {
  @Test
  fun `the upload preset asks for 1080p at 4 Mbps in H264 tone mapped to SDR`() {
    val upload = ExportSpec.Upload

    upload.heightPx shouldBe 1080
    upload.bitrate shouldBe Bitrate.mbps(4)
    upload.quality.shouldBeNull()
    upload.videoCodec shouldBe VideoCodec.H264
    upload.audioCodec shouldBe AudioCodec.Auto
    upload.frameRate.shouldBeNull()
    upload.hdr shouldBe HdrMode.ToneMapToSdr
    upload.strict shouldBe false
  }

  @Test
  fun `the share preset asks for 720p at 2 Mbps in H264 and AAC tone mapped to SDR`() {
    ExportSpec.Share shouldBe
      ExportSpec(
        heightPx = 720,
        bitrate = Bitrate.mbps(2),
        videoCodec = VideoCodec.H264,
        audioCodec = AudioCodec.Aac,
        hdr = HdrMode.ToneMapToSdr,
      )
  }

  @Test
  fun `the archive preset keeps the full size and HDR in HEVC and AAC`() {
    ExportSpec.Archive shouldBe
      ExportSpec(videoCodec = VideoCodec.Hevc, audioCodec = AudioCodec.Aac, hdr = HdrMode.KeepHdr)
  }

  @Test
  fun `the original preset only asks for the source's codec`() {
    ExportSpec.Original shouldBe ExportSpec(videoCodec = VideoCodec.MatchSource)
  }
}
