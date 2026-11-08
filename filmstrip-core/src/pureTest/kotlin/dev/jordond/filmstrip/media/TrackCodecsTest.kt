package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Reading each backend's spelling of a codec into the same kind.
 *
 * Four backends name one codec four ways, so the cases here are real spellings. A four-character
 * code from AVFoundation, a MIME type from Android's extractor, a bare name or a tag from ffprobe,
 * and a codecs parameter from WebCodecs.
 */
class TrackCodecsTest {
  @Test
  fun `reads every backend's spelling of the same codec`() {
    listOf("hvc1", "hev1", "video/hevc", "hevc", "hvc1.1.6.L93.B0").forEach { name ->
      withClue("$name is HEVC") { trackCodecOf(name).kind shouldBe CodecKind.Hevc }
    }
    listOf("avc1", "avc3", "video/avc", "h264", "avc1.640028").forEach { name ->
      withClue("$name is H.264") { trackCodecOf(name).kind shouldBe CodecKind.H264 }
    }
    listOf("mp4a", "audio/mp4a-latm", "aac", "mp4a.40.2").forEach { name ->
      withClue("$name is AAC") { trackCodecOf(name).kind shouldBe CodecKind.Aac }
    }
  }

  // Android spells VP9 with the codec buried in the middle, which is the case the token match
  // alone cannot reach.
  @Test
  fun `reads a vendor-shaped name`() {
    trackCodecOf("video/x-vnd.on2.vp9").kind shouldBe CodecKind.Vp9
    trackCodecOf("video/x-vnd.on2.vp8").kind shouldBe CodecKind.Vp8
  }

  @Test
  fun `keeps the platform's own spelling alongside the kind`() {
    val codec = trackCodecOf("video/hevc")

    withClue("the platform's spelling is what a bug report needs") { codec.name shouldBe "video/hevc" }
    codec.kind shouldBe CodecKind.Hevc
  }

  @Test
  fun `calls anything it does not recognise Other and does not fail`() {
    trackCodecOf("video/some-future-codec").kind shouldBe CodecKind.Other
    trackCodecOf("").kind shouldBe CodecKind.Other
  }

  @Test
  fun `names the video codec every kind a copy is permitted to touch`() {
    videoCodecOf(CodecKind.H264) shouldBe VideoCodec.H264
    videoCodecOf(CodecKind.Hevc) shouldBe VideoCodec.Hevc
    videoCodecOf(CodecKind.Vp8) shouldBe VideoCodec.Vp8
    videoCodecOf(CodecKind.Vp9) shouldBe VideoCodec.Vp9
    videoCodecOf(CodecKind.Av1) shouldBe VideoCodec.Av1
  }

  @Test
  fun `names the audio codec every kind a copy is permitted to touch`() {
    audioCodecOf(CodecKind.Aac) shouldBe AudioCodec.Aac
    audioCodecOf(CodecKind.Opus) shouldBe AudioCodec.Opus
    audioCodecOf(CodecKind.Vorbis) shouldBe AudioCodec.Vorbis
    audioCodecOf(CodecKind.Flac) shouldBe AudioCodec.Flac
    audioCodecOf(CodecKind.Mp3) shouldBe AudioCodec.Mp3
  }

  @Test
  fun `names no video codec for a kind that is not one rather than guessing one`() {
    videoCodecOf(CodecKind.Aac).shouldBeNull()
    videoCodecOf(CodecKind.Pcm).shouldBeNull()
    videoCodecOf(CodecKind.Other).shouldBeNull()
  }

  @Test
  fun `names no audio codec for a kind that is not one rather than guessing one`() {
    audioCodecOf(CodecKind.H264).shouldBeNull()
    audioCodecOf(CodecKind.Pcm).shouldBeNull()
    audioCodecOf(CodecKind.Other).shouldBeNull()
  }
}
