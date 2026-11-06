package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.geometry.frameWithin
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import dev.jordond.filmstrip.transform.internal.refusal
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * The codecs, audio format, frame rate, HDR handling and frame an export settles on against what the device encodes.
 */
class EncoderChoiceTest {
  private val fixture = PlannerFixture()

  @Test
  fun `Auto takes the first rung of the ladder the device encodes and concedes nothing`() {
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.Hevc)))

    val choice = resolveVideoCodec(ExportSpec(), device, false, LADDER, DEFAULT_HDR_LADDER).shouldNotBeNull()

    choice.codec shouldBe VideoCodec.Hevc
    choice.fallback.shouldBeNull()
  }

  @Test
  fun `an asked codec the device encodes is kept ahead of the ladder`() {
    val choice =
      resolveVideoCodec(
        ExportSpec(videoCodec = VideoCodec.Hevc),
        fixture.device(),
        false,
        LADDER,
        DEFAULT_HDR_LADDER,
      ).shouldNotBeNull()

    choice.codec shouldBe VideoCodec.Hevc
    choice.fallback.shouldBeNull()
  }

  @Test
  fun `an asked codec the device lacks falls down the ladder and is refused strictly by name`() {
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.Hevc)))

    val choice =
      resolveVideoCodec(
        ExportSpec(videoCodec = VideoCodec.Av1),
        device,
        false,
        LADDER,
        DEFAULT_HDR_LADDER,
      ).shouldNotBeNull()

    val message = "This device has no encoder for Av1, so Hevc is used instead."
    choice.codec shouldBe VideoCodec.Hevc
    val fallback = choice.fallback.shouldNotBeNull()
    fallback.adjustment shouldBe Adjustment(AdjustmentKind.CodecFallback, "Av1", "Hevc", message)
    val refusal = fallback.strictRefusal.shouldBeInstanceOf<FilmstripError.NoEncoder>()
    refusal.codec shouldBe VideoCodec.Av1
    refusal.message shouldBe message
  }

  @Test
  fun `a kept grade chooses from the HDR ladder whatever was asked`() {
    val choice =
      resolveVideoCodec(
        ExportSpec(videoCodec = VideoCodec.H264),
        fixture.device(),
        true,
        LADDER,
        DEFAULT_HDR_LADDER,
      ).shouldNotBeNull()

    choice.codec shouldBe DEFAULT_HDR_LADDER.first()
    choice.fallback
      .shouldNotBeNull()
      .adjustment.message shouldBe
      "Keeping the HDR grade needs a 10-bit encoder, so ${DEFAULT_HDR_LADDER.first()} is used instead."
  }

  @Test
  fun `no codec is chosen when the device encodes nothing the ladder tries`() {
    val h264Only = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264)))

    resolveVideoCodec(
      ExportSpec(),
      fixture.device(video = emptyList()),
      false,
      LADDER,
      DEFAULT_HDR_LADDER,
    ).shouldBeNull()
    resolveVideoCodec(ExportSpec(), h264Only, true, LADDER, DEFAULT_HDR_LADDER).shouldBeNull()
  }

  @Test
  fun `silent output carries no audio codec whatever was asked`() {
    val choice = resolveAudioCodec(ExportSpec(audioCodec = AudioCodec.Aac), fixture.device(), silent = true)

    choice.codec shouldBe AudioCodec.None
    choice.fallback.shouldBeNull()
  }

  @Test
  fun `Auto and a supported codec take an audio encoder without conceding`() {
    val device = fixture.device(audio = listOf(OPUS, AAC))

    resolveAudioCodec(ExportSpec(), device, silent = false).codec shouldBe AudioCodec.Opus
    resolveAudioCodec(ExportSpec(audioCodec = AudioCodec.Aac), device, silent = false).codec shouldBe AudioCodec.Aac
    resolveAudioCodec(ExportSpec(audioCodec = AudioCodec.Aac), device, silent = false).fallback.shouldBeNull()
  }

  @Test
  fun `an audio codec the device lacks falls to its first encoder and is refused strictly`() {
    val choice = resolveAudioCodec(ExportSpec(audioCodec = AudioCodec.Flac), fixture.device(), silent = false)

    val message = "This device has no Flac encoder, so Aac is used instead."
    choice.codec shouldBe AudioCodec.Aac
    val fallback = choice.fallback.shouldNotBeNull()
    fallback.adjustment shouldBe Adjustment(AdjustmentKind.CodecFallback, "Flac", "Aac", message)
    fallback.strictRefusal.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe message
  }

  @Test
  fun `a device with no audio encoder writes no audio and reports nothing`() {
    val choice = resolveAudioCodec(ExportSpec(audioCodec = AudioCodec.Aac), fixture.device(audio = emptyList()), false)

    choice.codec shouldBe AudioCodec.None
    choice.fallback.shouldBeNull()
  }

  @Test
  fun `the first clip with audio proposes the rate and an encoder that lists it keeps it`() {
    val tracks = tracksOf(fixture.clip(), fixture.clip(audioRate = 48_000), fixture.clip(audioRate = 32_000))

    audioFormat(tracks, aac(listOf(32_000, 48_000), 2)) shouldBe AudioFormat(48_000, 2)
  }

  // Both rates sit off the list, one nearer the rate below and one nearer the rate above.
  @Test
  fun `a rate the encoder does not list snaps to the nearest one it does`() {
    val rates = listOf(32_000, 44_100, 48_000)

    audioFormat(tracksOf(fixture.clip(audioRate = 37_800)), aac(rates, 2)).sampleRate shouldBe 32_000
    audioFormat(tracksOf(fixture.clip(audioRate = 40_000)), aac(rates, 2)).sampleRate shouldBe 44_100
  }

  @Test
  fun `an encoder that lists no rates is handed the source's own`() {
    val tracks = tracksOf(fixture.clip(audioRate = 37_800))

    audioFormat(tracks, aac(emptyList(), 2)) shouldBe AudioFormat(37_800, 2)
    audioFormat(tracks, null) shouldBe AudioFormat(37_800, 2)
  }

  @Test
  fun `stereo is held under the encoder's channel count and a count it does not give is ignored`() {
    val tracks = tracksOf(fixture.clip(audioRate = 48_000))

    audioFormat(tracks, aac(listOf(48_000), 1)).channelCount shouldBe 1
    audioFormat(tracks, aac(listOf(48_000), 6)).channelCount shouldBe 2
    audioFormat(tracks, aac(listOf(48_000), 0)).channelCount shouldBe 2
  }

  @Test
  fun `tracks with no audio at all fall back to 44_1 kHz`() {
    audioFormat(tracksOf(fixture.clip()), null) shouldBe AudioFormat(44_100, 2)
  }

  @Test
  fun `the asked rate wins then the source's rounded rate and the encoder's ceiling holds either`() {
    resolveFrameRate(ExportSpec(frameRate = 24), 59.94f, null) shouldBe 24
    resolveFrameRate(ExportSpec(), 29.97f, null) shouldBe 30
    resolveFrameRate(ExportSpec(), 0f, null) shouldBe 30
    resolveFrameRate(ExportSpec(), null, null) shouldBe 30
    resolveFrameRate(ExportSpec(), 59.94f, 48) shouldBe 48
    resolveFrameRate(ExportSpec(frameRate = 120), null, 60) shouldBe 60
    resolveFrameRate(ExportSpec(frameRate = 120), null, 0) shouldBe 120
  }

  @Test
  fun `an SDR source keeps its grade whatever the mode and whatever the device`() {
    for (mode in HdrMode.entries) {
      resolveHdr(mode, false, gradesAgree = false, false, canToneMap = false, copyKeepsHdr = false) shouldBe
        ResolvedHdr.Keep
    }
  }

  @Test
  fun `an HDR grade is kept by an encoder or a copy only while every source agrees on it`() {
    resolveHdr(HdrMode.Auto, true, true, deviceEncodesHdr = true, true, copyKeepsHdr = false) shouldBe ResolvedHdr.Keep
    resolveHdr(HdrMode.Auto, true, true, deviceEncodesHdr = false, true, copyKeepsHdr = true) shouldBe ResolvedHdr.Keep
    resolveHdr(HdrMode.Auto, true, gradesAgree = false, true, true, true) shouldBe ResolvedHdr.ToneMap
    resolveHdr(HdrMode.KeepHdr, true, gradesAgree = false, true, true, true).shouldBeNull()
    resolveHdr(HdrMode.KeepHdr, true, true, deviceEncodesHdr = false, true, copyKeepsHdr = false).shouldBeNull()
  }

  @Test
  fun `a tone map needs the engine to do one and an explicit one is never kept`() {
    resolveHdr(HdrMode.ToneMapToSdr, true, true, true, canToneMap = true, copyKeepsHdr = true) shouldBe
      ResolvedHdr.ToneMap
    resolveHdr(HdrMode.ToneMapToSdr, true, true, true, canToneMap = false, copyKeepsHdr = true).shouldBeNull()
    resolveHdr(HdrMode.Auto, true, gradesAgree = false, true, canToneMap = false, copyKeepsHdr = true).shouldBeNull()
  }

  @Test
  fun `a frame over the ceiling fits inside it the way frameWithin does before aligning`() {
    val max = Size(1920, 1080)
    val source = Size(4000, 3000)
    val within = frameWithin(source, max)

    source.fittedTo(max, 16) shouldBe Size(within.width - within.width % 16, within.height - within.height % 16)
    source.fittedTo(max, 2) shouldBe within
  }

  // Rounding 1080 to the nearest multiple of 16 is 1088, past the maximum the frame already sat on.
  @Test
  fun `each side rounds down to the alignment so a frame on the maximum stays inside it`() {
    Size(1920, 1080).fittedTo(Size(1920, 1080), 16) shouldBe Size(1920, 1072)
    Size(1000, 600).fittedTo(null, 16) shouldBe Size(992, 592)
  }

  @Test
  fun `a side below the alignment grows to one step and no alignment is taken as one`() {
    Size(10, 4).fittedTo(null, 16) shouldBe Size(16, 16)
    Size(1001, 601).fittedTo(null, 0) shouldBe Size(1001, 601)
  }

  @Test
  fun `the tightest box takes the smaller of each side or whichever box was given`() {
    tightest(Size(1920, 2000), Size(3840, 1080)) shouldBe Size(1920, 1080)
    tightest(null, Size(640, 480)) shouldBe Size(640, 480)
    tightest(Size(640, 480), null) shouldBe Size(640, 480)
    tightest(null, null).shouldBeNull()
  }

  @Test
  fun `a copy writes the source's own codecs and audio format`() {
    val clip = fixture.clip(codec = "hvc1", audioRate = 37_800)
    val edit = composition(clip)
    val infos = fixture.infos(edit)
    val ask = Ask(ExportSpec(), fixture.device(), infos, emptyMap(), null)

    val format =
      outputFormatOf(
        ask,
        framingOf(clip, infos.getValue(clip.source)),
        gradeOf(path = ExportPath.Copy),
        encodingOf(SIZE, frameRate = 30),
        CodecChoice(AudioCodec.Opus, null),
        fixture.resolve(edit).tracks,
      )

    format shouldBe OutputFormat(SIZE, VideoCodec.Hevc, AudioCodec.Aac, null, 30, AudioFormat(37_800, 2))
  }

  @Test
  fun `a transcode writes the chosen codecs and the format the encoder takes`() {
    val clip = fixture.clip(codec = "hvc1", audioRate = 37_800)
    val edit = composition(clip)
    val infos = fixture.infos(edit)
    val device = fixture.device(audio = listOf(aac(listOf(44_100, 48_000), 1)))
    val ask = Ask(ExportSpec(), device, infos, emptyMap(), null)
    val framing = framingOf(clip, infos.getValue(clip.source))
    val tracks = fixture.resolve(edit).tracks

    val encoded =
      outputFormatOf(
        ask,
        framing,
        gradeOf(),
        encodingOf(SIZE, frameRate = 25),
        CodecChoice(AudioCodec.Aac, null),
        tracks,
      )
    val silent =
      outputFormatOf(
        ask,
        framing,
        gradeOf(),
        encodingOf(SIZE, frameRate = 25),
        CodecChoice(AudioCodec.None, null),
        tracks,
      )

    encoded shouldBe OutputFormat(SIZE, VideoCodec.H264, AudioCodec.Aac, null, 25, AudioFormat(44_100, 1))
    silent.audioCodec shouldBe AudioCodec.None
    silent.audioFormat.shouldBeNull()
  }

  private fun tracksOf(vararg clips: Clip): List<ResolvedTrack> = fixture.resolve(composition(*clips)).tracks

  private fun aac(
    rates: List<Int>,
    channels: Int,
  ) = AudioEncoderCapability(AudioCodec.Aac, rates, channels)

  private companion object {
    val SIZE = Size(1920, 1080)
    val LADDER = listOf(VideoCodec.H264, VideoCodec.Hevc)
    val AAC = AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2)
    val OPUS = AudioEncoderCapability(AudioCodec.Opus, listOf(48_000), 2)
  }
}
