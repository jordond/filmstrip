package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.ffmpeg.internal.probe.drift
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.TrackCodec
import dev.jordond.filmstrip.media.VideoTrackInfo
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What [drift] reports when the file ffmpeg wrote is read back against the plan it was written for.
 *
 * Each case changes one field of a file that otherwise matches, so the message names the field that moved.
 */
class OutputDriftTest {
  @Test
  fun `a file that matches the plan has no drift`() {
    drift(plan(), written()) shouldBe null
  }

  @Test
  fun `a missing video track drifts`() {
    drift(plan(), written(video = null)) shouldBe "The written file has no video track, but the plan asked for one."
  }

  @Test
  fun `a different frame size drifts`() {
    drift(plan(), written(video = video(displaySize = Size(1280, 720)))) shouldBe
      "The written file is 1280x720, and the plan asked for 1920x1080."
  }

  @Test
  fun `a different video codec drifts`() {
    drift(plan(), written(video = video(codec = TrackCodec("hev1", CodecKind.Hevc)))) shouldBe
      "The written file's video codec is hev1, and the plan asked for H264."
  }

  @Test
  fun `a video codec it cannot name is not drift`() {
    drift(plan(), written(video = video(codec = TrackCodec("prores", CodecKind.Other)))) shouldBe null
  }

  // The tolerance is half a frame per second either way, so a rate inside it reads as the planned one.
  @Test
  fun `a frame rate within the tolerance is not drift`() {
    drift(plan(), written(video = video(frameRate = 30.4f))) shouldBe null
    drift(plan(), written(video = video(frameRate = 29.6f))) shouldBe null
  }

  @Test
  fun `a frame rate past the tolerance drifts`() {
    drift(plan(), written(video = video(frameRate = 25f))) shouldBe
      "The written file runs at 25.0 fps, and the plan asked for 30."
  }

  @Test
  fun `a frame rate the file does not report is not drift`() {
    drift(plan(), written(video = video(frameRate = null))) shouldBe null
  }

  @Test
  fun `a missing audio track drifts when the plan asked for one`() {
    drift(plan(), written(audio = null)) shouldBe "The written file has no audio track, but the plan asked for one."
  }

  @Test
  fun `a missing audio track is not drift when the plan asked for none`() {
    drift(plan(audioFormat = null), written(audio = null)) shouldBe null
  }

  @Test
  fun `a different sample rate or channel count drifts`() {
    val expected = "The written file's audio is 44100 Hz with 2 channels, and the plan asked for 48000 Hz with 2."
    drift(plan(), written(audio = audio(sampleRate = 44_100))) shouldBe expected

    drift(plan(), written(audio = audio(channelCount = 1))) shouldBe
      "The written file's audio is 48000 Hz with 1 channels, and the plan asked for 48000 Hz with 2."
  }

  @Test
  fun `a different audio codec drifts`() {
    drift(plan(), written(audio = audio(codec = TrackCodec("opus", CodecKind.Opus)))) shouldBe
      "The written file's audio codec is opus, and the plan asked for Aac."
  }

  @Test
  fun `an audio codec it cannot name is not drift`() {
    drift(plan(), written(audio = audio(codec = TrackCodec("pcm_s16le", CodecKind.Pcm)))) shouldBe null
  }

  @Test
  fun `an audio only plan drifts on a written video track`() {
    drift(plan(audio = AudioSpec.AudioOnly), written()) shouldBe
      "The written file has a video track, and the plan asked for audio only."
  }

  @Test
  fun `an audio only plan with no video track has no drift`() {
    drift(plan(audio = AudioSpec.AudioOnly), written(video = null)) shouldBe null
  }

  private fun plan(
    audio: AudioSpec = AudioSpec.Keep,
    audioFormat: AudioFormat? = AudioFormat(sampleRate = 48_000, channelCount = 2),
  ): ExportPlan =
    ExportPlan(
      path = ExportPath.Transcode,
      output =
        OutputFormat(
          size = SIZE,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = audioFormat,
        ),
      effectOrder = emptyList(),
      estimate = ExportEstimate(null, null, null, isCopy = false),
      parity = EffectParity.Exact,
      duration = DURATION,
      copyBlockedBy = emptyList(),
      composition =
        compositionOf {
          clip(MediaSource.of("clip.mp4"))
          audio(audio)
        },
      spec = ExportSpec(),
    )

  private fun written(
    video: VideoTrackInfo? = video(),
    audio: AudioTrackInfo? = audio(),
  ): MediaInfo = MediaInfo(DURATION, video, audio, isExportable = true)

  private fun video(
    displaySize: Size = SIZE,
    frameRate: Float? = 30f,
    codec: TrackCodec = TrackCodec("avc1", CodecKind.H264),
  ): VideoTrackInfo =
    VideoTrackInfo(
      codedSize = displaySize,
      displaySize = displaySize,
      rotationDegrees = 0,
      pixelAspectRatio = 1f,
      frameRate = frameRate,
      codec = codec,
      bitDepth = 8,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      bitrate = null,
    )

  private fun audio(
    sampleRate: Int = 48_000,
    channelCount: Int = 2,
    codec: TrackCodec = TrackCodec("mp4a", CodecKind.Aac),
  ): AudioTrackInfo = AudioTrackInfo(codec, sampleRate, channelCount, bitrate = null)

  private companion object {
    val SIZE = Size(1920, 1080)
    val DURATION = 2.seconds
  }
}
