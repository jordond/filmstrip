package dev.jordond.filmstrip.transform.internal.copy

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.TrackCodec
import dev.jordond.filmstrip.media.VideoTrackInfo
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The one answer every backend here reads for whether a source's streams can go into mp4 untouched.
 *
 * Four backends copy into mp4 and a wrong answer is silent: the plan promises a copy and the muxer
 * either refuses it or the backend quietly re-encodes instead.
 */
class Mp4CopyTest {
  @Test
  fun `mp4 carries the codecs it is specified to carry`() {
    CodecKind.entries.filter { it in Mp4Copy.VIDEO }.forEach { kind ->
      withClue("$kind is listed as an mp4 video codec") { Mp4Copy.accepts(info(video = kind)).shouldBeTrue() }
    }
    CodecKind.entries.filter { it in Mp4Copy.AUDIO }.forEach { kind ->
      withClue("$kind is listed as an mp4 audio codec") { Mp4Copy.accepts(info(audio = kind)).shouldBeTrue() }
    }
  }

  @Test
  fun `a codec mp4 does not carry is refused on either track`() {
    withClue("mp4 has no sample entry for VP8") { Mp4Copy.accepts(info(video = CodecKind.Vp8)).shouldBeFalse() }
    withClue("mp4 has no sample entry for Vorbis") { Mp4Copy.accepts(info(audio = CodecKind.Vorbis)).shouldBeFalse() }
    Mp4Copy.accepts(info(video = CodecKind.Other)).shouldBeFalse()
    Mp4Copy.accepts(info(audio = CodecKind.Pcm)).shouldBeFalse()
  }

  // Both tracks have to go across, since a copy writes one file and half of one is no use.
  @Test
  fun `a carryable video track does not carry an audio track mp4 refuses`() {
    Mp4Copy.accepts(info(video = CodecKind.Hevc, audio = CodecKind.Vorbis)).shouldBeFalse()
    Mp4Copy.accepts(info(video = CodecKind.Vp8, audio = CodecKind.Aac)).shouldBeFalse()
  }

  @Test
  fun `a source with no audio passes on the audio half`() {
    Mp4Copy.accepts(info(video = CodecKind.H264, audio = null)).shouldBeTrue()
    Mp4Copy.accepts(info(video = CodecKind.Vp8, audio = null)).shouldBeFalse()
  }

  @Test
  fun `a source with no video track never copies`() {
    Mp4Copy
      .accepts(
        MediaInfo(1.seconds, video = null, audioTracks = listOfNotNull(track(CodecKind.Aac)), isExportable = true),
      ).shouldBeFalse()
  }

  // A muxer that carries more than mp4 otherwise does says so at its own call site rather than
  // widening the baseline every other backend reads.
  @Test
  fun `a muxer's own additions widen only its own answer`() {
    val vp8 = info(video = CodecKind.Vp8, audio = CodecKind.Vorbis)

    Mp4Copy.accepts(vp8).shouldBeFalse()
    Mp4Copy.accepts(vp8, alsoVideo = setOf(CodecKind.Vp8), alsoAudio = setOf(CodecKind.Vorbis)).shouldBeTrue()
  }

  private fun info(
    video: CodecKind = CodecKind.H264,
    audio: CodecKind? = CodecKind.Aac,
  ) = MediaInfo(
    duration = 1.seconds,
    video =
      VideoTrackInfo(
        codedSize = Size(1920, 1080),
        displaySize = Size(1920, 1080),
        rotationDegrees = 0,
        pixelAspectRatio = 1f,
        frameRate = 30f,
        codec = TrackCodec(video.name, video),
        bitDepth = 8,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        bitrate = null,
        hasAlpha = false,
      ),
    audioTracks = listOfNotNull(audio?.let { track(it) }),
    isExportable = true,
  )

  private fun track(kind: CodecKind) =
    AudioTrackInfo(AudioTrackId(1), TrackCodec(kind.name, kind), 48_000, 2, null, language = null, isDefault = true)
}
