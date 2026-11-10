package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.probe.parseMediaInfo
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class ProbeReportTest {
  @Test
  fun `reads both tracks and the container duration`() {
    val info = parseMediaInfo(PROBE)!!

    info.duration shouldBe 6_000.milliseconds
    info.video!!.codedSize shouldBe Size(1920, 1080)
    info.video!!.displaySize shouldBe Size(1920, 1080)
    info.video!!.frameRate shouldBe 30f
    info.video!!.codec.name shouldBe "avc1"
    info.video!!.codec.kind shouldBe CodecKind.H264
    info.video!!.colorSpace shouldBe ColorSpace.Bt709
    info.audio!!.sampleRate shouldBe 48_000
    info.audio!!.channelCount shouldBe 2
  }

  // A display matrix reports the rotation to undo, so -90 in the side data is a clockwise source
  // and the upright frame is portrait.
  @Test
  fun `reads rotation out of the side data`() {
    val info = parseMediaInfo(ROTATED)!!

    info.video!!.rotationDegrees shouldBe 90
    info.video!!.displaySize shouldBe Size(1080, 1920)
  }

  // Anamorphic HDV stores 1440 wide pixels that play back at 1920, and the stretch lands on the
  // stored width, so a quarter turn moves it to the display height.
  @Test
  fun `stretches an anamorphic frame before it turns it`() {
    val info = parseMediaInfo(ANAMORPHIC)!!

    info.video!!.codedSize shouldBe Size(1440, 1080)
    info.video!!.pixelAspectRatio shouldBe 4f / 3f
    info.video!!.displaySize shouldBe Size(1920, 1080)

    parseMediaInfo(ROTATED_ANAMORPHIC)!!.video!!.displaySize shouldBe Size(1080, 1920)
  }

  // A transport stream's PID is an id no other prober reads, so only the MP4 one names its track.
  @Test
  fun `names a track by its mp4 id and any other container's by its place`() {
    fun idsOf(format: String): List<Long> = parseMediaInfo(threeAudio(format))!!.audioTracks.map { it.id.value }

    idsOf("mov,mp4,m4a,3gp,3g2,mj2") shouldBe listOf(2L, 3L, 4L)
    idsOf("mpegts") shouldBe listOf(1L, 2L, 3L)
  }

  @Test
  fun `reports nothing for a file with no tracks`() {
    parseMediaInfo("[FORMAT]\nduration=1.0\n[/FORMAT]") shouldBe null
  }

  @Test
  fun `a pixel format with an alpha plane reads as alpha and one without does not`() {
    listOf("yuva444p12le", "yuva420p", "rgba", "gbrap10le", "ya8").forEach {
      parseMediaInfo(probeWith(it))!!.video!!.hasAlpha shouldBe true
    }
    listOf("yuv420p", "yuv444p12le", "rgb24", "gray").forEach {
      parseMediaInfo(probeWith(it))!!.video!!.hasAlpha shouldBe false
    }
  }

  private companion object {
    fun probeWith(pixelFormat: String) =
      """
      [STREAM]
      index=0
      codec_name=prores
      codec_type=video
      width=64
      height=64
      pix_fmt=$pixelFormat
      [/STREAM]
      [FORMAT]
      duration=1.000000
      [/FORMAT]
      """.trimIndent()

    val PROBE =
      """
      [STREAM]
      index=0
      codec_name=h264
      codec_type=video
      codec_tag_string=avc1
      width=1920
      height=1080
      sample_aspect_ratio=1:1
      avg_frame_rate=30/1
      pix_fmt=yuv420p
      color_space=bt709
      bit_rate=12000000
      [/STREAM]
      [STREAM]
      index=1
      codec_name=aac
      codec_type=audio
      codec_tag_string=mp4a
      sample_rate=48000
      channels=2
      [/STREAM]
      [FORMAT]
      duration=6.000000
      size=9000000
      [/FORMAT]
      """.trimIndent()

    fun threeAudio(format: String): String =
      (2..4).joinToString("\n") { id ->
        "[STREAM]\nindex=${id - 1}\ncodec_name=aac\ncodec_type=audio\nid=0x$id\n" +
          "sample_rate=48000\nchannels=2\n[/STREAM]"
      } + "\n[FORMAT]\nformat_name=$format\nduration=2.000000\n[/FORMAT]"

    val ANAMORPHIC =
      """
      [STREAM]
      index=0
      codec_name=h264
      codec_type=video
      width=1440
      height=1080
      sample_aspect_ratio=4:3
      avg_frame_rate=30/1
      [/STREAM]
      [FORMAT]
      duration=3.000000
      [/FORMAT]
      """.trimIndent()

    val ROTATED_ANAMORPHIC =
      """
      [STREAM]
      index=0
      codec_name=h264
      codec_type=video
      width=1440
      height=1080
      sample_aspect_ratio=4:3
      avg_frame_rate=30/1
      [SIDE_DATA]
      side_data_type=Display Matrix
      rotation=-90
      [/SIDE_DATA]
      [/STREAM]
      [FORMAT]
      duration=3.000000
      [/FORMAT]
      """.trimIndent()

    val ROTATED =
      """
      [STREAM]
      index=0
      codec_type=video
      codec_tag_string=avc1
      width=1920
      height=1080
      sample_aspect_ratio=1:1
      avg_frame_rate=30/1
      [SIDE_DATA]
      side_data_type=Display Matrix
      rotation=-90
      [/SIDE_DATA]
      [/STREAM]
      [FORMAT]
      duration=3.000000
      [/FORMAT]
      """.trimIndent()
  }
}
