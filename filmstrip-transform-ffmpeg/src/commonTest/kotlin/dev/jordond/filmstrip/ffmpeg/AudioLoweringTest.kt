package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.ffmpeg.internal.lowering.AudioLowering
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FilterGraphBuilder
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.ffmpeg.internal.lowering.volumeExpressions
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.resolve.GainSegment
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// AudioLowering on its own, over a hand-built plan, so each join, delay and mix is read off the
// chains it writes rather than through a whole export graph.
class AudioLoweringTest {
  @Test
  fun `a plan with no track carrying sound writes silence for the whole composition`() {
    val lowered = lower(track(TrackContent.Video, clip(2.seconds)), duration = 2.seconds)

    lowered.output shouldBe "aout"
    val generated = lowered.inputs.single()
    generated.source.shouldBeInstanceOf<InputSource.Generated>().description shouldBe "anullsrc=r=48000:cl=stereo"
    generated.durationSeconds shouldBe 2.0
    lowered.graph shouldBe "[0:a]asetpts=expr=PTS-STARTPTS,$STEREO_FORMAT[aout]"
  }

  @Test
  fun `a single clip is trimmed to its window and cut to the composition`() {
    val lowered = lower(track(TrackContent.AudioAndVideo, clip(1.seconds, from = 500.milliseconds)), 1.seconds)

    lowered.inputs.size shouldBe 0
    lowered.graph shouldContain
      "[t0c0]atrim=start=${formatSeconds(0.5)}:end=${formatSeconds(1.5)},asetpts=expr=PTS-STARTPTS," +
      "$STEREO_FORMAT[a0c0]"
    lowered.graph shouldContain "[a0c0]atrim=end=${formatSeconds(1.0)},asetpts=expr=PTS-STARTPTS," +
      "aresample=async=1:first_pts=0,$STEREO_FORMAT[aout]"
  }

  // The silent clip contributes generated silence of its own length, so the clip after it opens
  // where the plan laid it rather than early by the silent clip's run.
  @Test
  fun `a clip with no audio holds its slot with silence of its own length`() {
    val silent = clip(750.milliseconds, hasAudio = false)
    val lowered = lower(track(TrackContent.AudioAndVideo, clip(1.seconds), silent, clip(1.seconds)), 2_750.milliseconds)

    val filler = lowered.inputs.single()
    filler.source.shouldBeInstanceOf<InputSource.Generated>().description shouldBe "anullsrc=r=48000:cl=stereo"
    filler.durationSeconds shouldBe 0.75
    lowered.graph shouldContain "[0:a]asetpts=expr=PTS-STARTPTS,$STEREO_FORMAT[a0c1]"
    lowered.graph shouldContain "[t0c2]atrim"
    lowered.graph shouldContain "[a0c0][a0c1][a0c2]concat=n=3:v=0:a=1[at0]"
  }

  @Test
  fun `a track that opens late is delayed to its own start`() {
    val bed = track(TrackContent.Audio, clip(1.seconds), start = 1_250.milliseconds)

    val lowered = lower(bed, duration = 2_250.milliseconds)

    lowered.graph shouldContain "[a0c0]adelay=delays=1250:all=1[ad0]"
    lowered.graph shouldContain "[ad0]atrim=end="
  }

  // The lead is as long as the longest track, here the bed that opens late and runs on past the
  // primary, and every track is mixed without amix dividing it by the input count.
  @Test
  fun `two tracks mix behind a silent lead as long as the longer one`() {
    val primary = track(TrackContent.AudioAndVideo, clip(2.seconds))
    val bed = track(TrackContent.Audio, clip(1_500.milliseconds), start = 1.seconds)

    val lowered = lower(primary, bed, duration = 2_500.milliseconds)

    lowered.graph shouldContain
      "anullsrc=r=48000:cl=stereo,atrim=end=${formatSeconds(2.5)},$STEREO_FORMAT[alead]"
    lowered.graph shouldContain
      "[alead][a0c0][ad1]amix=inputs=3:duration=longest:dropout_transition=0:normalize=0[amixed]"
    lowered.graph shouldContain "[amixed]atrim=end=${formatSeconds(2.5)}"
  }

  @Test
  fun `a video only track takes no part in the mix`() {
    val picture = track(TrackContent.Video, clip(2.seconds))
    val bed = track(TrackContent.Audio, clip(2.seconds))

    val lowered = lower(picture, bed, duration = 2.seconds)

    lowered.graph shouldNotContain "amix"
    lowered.graph shouldNotContain "a0c0"
    lowered.graph shouldContain "[a1c0]atrim=end="
  }

  @Test
  fun `a constant gain is one volume and unity is none`() {
    val quiet = lower(track(TrackContent.Audio, clip(1.seconds, gain = 0.5f)), 1.seconds)
    val unity = lower(track(TrackContent.Audio, clip(1.seconds)), 1.seconds)

    quiet.graph shouldContain "$STEREO_FORMAT,volume=volume=0.5[a0c0]"
    unity.graph shouldNotContain "volume"
  }

  // The expressions are the shared curve's own, so the test reads them off volumeExpressions
  // rather than a copied string.
  @Test
  fun `a ramping gain is cut into small frames and evaluated per frame`() {
    val ramp =
      ResolvedGain(
        listOf(
          GainSegment(Duration.ZERO, 400.milliseconds, 0.2f, 0.8f),
          GainSegment(400.milliseconds, 1.seconds, 0.8f, 0.3f),
        ),
      )
    val lowered = lower(track(TrackContent.Audio, clip(1.seconds, curve = ramp)), 1.seconds)

    val volumes = volumeExpressions(ramp).map { FilterNode("volume", "volume" to it, "eval" to "frame") }.render()
    lowered.graph shouldContain "$STEREO_FORMAT,asetnsamples=n=64:p=0,$volumes[a0c0]"
  }

  @Test
  fun `a mono output pins every branch to a mono layout`() {
    val lowered = lower(track(TrackContent.Audio, clip(1.seconds)), duration = 1.seconds, channels = 1)

    lowered.graph shouldContain "channel_layouts=mono"
    lowered.graph shouldNotContain "channel_layouts=stereo"
  }

  @Test
  fun `a plan with no audio format is refused`() {
    val negotiated = composition(listOf(track(TrackContent.Audio, clip(1.seconds))), 1.seconds, format = null)

    assertFailsWith<IllegalStateException> {
      AudioLowering(negotiated, FilterGraphBuilder(), mutableListOf()) { _, _ -> "" }
    }
  }

  private class Lowered(
    val output: String,
    val graph: String,
    val inputs: List<InputSpec>,
  )

  private fun lower(
    vararg tracks: ResolvedTrack,
    duration: Duration,
    channels: Int = 2,
  ): Lowered {
    val graph = FilterGraphBuilder()
    val inputs = mutableListOf<InputSpec>()
    val format = AudioFormat(sampleRate = 48_000, channelCount = channels)
    val output =
      AudioLowering(composition(tracks.toList(), duration, format), graph, inputs) { track, clip -> "t${track}c$clip" }
        .build()
    return Lowered(output, graph.build(output), inputs)
  }

  private fun lower(
    track: ResolvedTrack,
    duration: Duration,
    channels: Int = 2,
  ): Lowered = lower(*arrayOf(track), duration = duration, channels = channels)

  private fun track(
    content: TrackContent,
    vararg clips: ResolvedClip,
    start: Duration = Duration.ZERO,
  ): ResolvedTrack {
    var at = start
    val laid = clips.map { it.laidAt(at).also { _ -> at += it.duration } }
    return ResolvedTrack(content = content, looping = false, start = start, clips = laid)
  }

  private fun clip(
    length: Duration,
    from: Duration = Duration.ZERO,
    hasAudio: Boolean = true,
    gain: Float = 1f,
    curve: ResolvedGain = ResolvedGain.constant(gain, Duration.ZERO, length),
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of("/clips/sound.m4a"),
      info =
        MediaInfo(
          duration = 10.seconds,
          video = null,
          audio = if (hasAudio) AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null) else null,
          isExportable = true,
        ),
      start = from,
      end = from + length,
      effects = emptyList(),
      gain = curve,
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, length),
      sourceIndex = 0,
    )

  private fun ResolvedClip.laidAt(at: Duration): ResolvedClip =
    ResolvedClip(
      source = source,
      info = info,
      start = start,
      end = end,
      effects = effects,
      gain = gain,
      startsAtKeyFrame = startsAtKeyFrame,
      span = TimeRange.of(at, at + duration),
      sourceIndex = sourceIndex,
    )

  private fun composition(
    tracks: List<ResolvedTrack>,
    duration: Duration,
    format: AudioFormat?,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks = tracks,
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = OUTPUT,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = format,
        ),
      layoutSize = OUTPUT,
      fill = Fill.Black,
      duration = duration,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      path = ExportPath.Transcode,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
    )

  private companion object {
    val OUTPUT = Size(1280, 720)
    const val STEREO_FORMAT = "aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo"
  }
}
