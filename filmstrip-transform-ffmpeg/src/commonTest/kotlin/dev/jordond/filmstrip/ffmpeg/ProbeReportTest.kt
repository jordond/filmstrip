package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.parseMediaInfo
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

  @Test
  fun `reports nothing for a file with no tracks`() {
    parseMediaInfo("[FORMAT]\nduration=1.0\n[/FORMAT]") shouldBe null
  }

  private companion object {
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
