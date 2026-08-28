package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reading each backend's spelling of a codec into the same kind.
 *
 * Four backends name one codec four ways, so the cases here are real spellings. A four-character
 * code from AVFoundation, a MIME type from Android's extractor, a bare name or a tag from ffprobe,
 * and a codecs parameter from WebCodecs.
 */
@OptIn(InternalFilmstripApi::class)
class TrackCodecsTest {
  @Test
  fun `reads every backend's spelling of the same codec`() {
    listOf("hvc1", "hev1", "video/hevc", "hevc", "hvc1.1.6.L93.B0").forEach { name ->
      assertEquals(CodecKind.Hevc, trackCodecOf(name).kind, "$name is HEVC")
    }
    listOf("avc1", "avc3", "video/avc", "h264", "avc1.640028").forEach { name ->
      assertEquals(CodecKind.H264, trackCodecOf(name).kind, "$name is H.264")
    }
    listOf("mp4a", "audio/mp4a-latm", "aac", "mp4a.40.2").forEach { name ->
      assertEquals(CodecKind.Aac, trackCodecOf(name).kind, "$name is AAC")
    }
  }

  // Android spells VP9 with the codec buried in the middle, which is the case the token match
  // alone cannot reach.
  @Test
  fun `reads a vendor-shaped name`() {
    assertEquals(CodecKind.Vp9, trackCodecOf("video/x-vnd.on2.vp9").kind)
    assertEquals(CodecKind.Vp8, trackCodecOf("video/x-vnd.on2.vp8").kind)
  }

  @Test
  fun `keeps the platform's own spelling alongside the kind`() {
    val codec = trackCodecOf("video/hevc")

    assertEquals("video/hevc", codec.name, "the platform's spelling is what a bug report needs")
    assertEquals(CodecKind.Hevc, codec.kind)
  }

  @Test
  fun `calls anything it does not recognise Other and does not fail`() {
    assertEquals(CodecKind.Other, trackCodecOf("video/some-future-codec").kind)
    assertEquals(CodecKind.Other, trackCodecOf("").kind)
  }

  @Test
  fun `names the video codec every kind a copy is permitted to touch`() {
    assertEquals(VideoCodec.H264, videoCodecOf(CodecKind.H264))
    assertEquals(VideoCodec.Hevc, videoCodecOf(CodecKind.Hevc))
    assertEquals(VideoCodec.Vp8, videoCodecOf(CodecKind.Vp8))
    assertEquals(VideoCodec.Vp9, videoCodecOf(CodecKind.Vp9))
    assertEquals(VideoCodec.Av1, videoCodecOf(CodecKind.Av1))
  }

  @Test
  fun `names the audio codec every kind a copy is permitted to touch`() {
    assertEquals(AudioCodec.Aac, audioCodecOf(CodecKind.Aac))
    assertEquals(AudioCodec.Opus, audioCodecOf(CodecKind.Opus))
    assertEquals(AudioCodec.Vorbis, audioCodecOf(CodecKind.Vorbis))
    assertEquals(AudioCodec.Flac, audioCodecOf(CodecKind.Flac))
    assertEquals(AudioCodec.Mp3, audioCodecOf(CodecKind.Mp3))
  }

  @Test
  fun `refuses a kind that names no video codec rather than guessing one`() {
    assertFailsWith<IllegalStateException> { videoCodecOf(CodecKind.Aac) }
    assertFailsWith<IllegalStateException> { videoCodecOf(CodecKind.Pcm) }
    assertFailsWith<IllegalStateException> { videoCodecOf(CodecKind.Other) }
  }

  @Test
  fun `refuses a kind that names no audio codec rather than guessing one`() {
    assertFailsWith<IllegalStateException> { audioCodecOf(CodecKind.H264) }
    assertFailsWith<IllegalStateException> { audioCodecOf(CodecKind.Pcm) }
    assertFailsWith<IllegalStateException> { audioCodecOf(CodecKind.Other) }
  }
}
