package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.Crop
import dev.jordond.filmstrip.effects.Flip
import dev.jordond.filmstrip.effects.Rotate
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.TrimStrategy
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserLowering
import dev.jordond.filmstrip.webcodecs.internal.BrowserPlanner
import dev.jordond.filmstrip.webcodecs.internal.HDR_VP9_CODEC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

// The browser planner, with fabricated probes. The lowering is pure, so none of these need
// mediabunny or a browser beyond what the runner already is.
@OptIn(InternalFilmstripApi::class)
class BrowserPlannerTest {
  private val planner = BrowserPlanner(listOf(BuiltInEffectResolver()))

  private fun lower(
    composition: EditComposition,
    spec: ExportSpec = videoSpec(),
    infos: Map<MediaSource, MediaInfo> = composition.clips.map { it.source to info() }.toMap(),
    device: DeviceCapabilities = device(VideoCodec.H264, VideoCodec.Vp9),
  ): BrowserLowering = planner.lower(composition, spec, device, infos)

  @Test
  fun emptyCompositionIsIncapable() {
    val verdict = lower(EditComposition(tracks = emptyList())).verdict
    assertIs<Verdict.Incapable>(verdict)
  }

  @Test
  fun audioIsAllowedWhetherKeptOrRemoved() {
    val spec = ExportSpec(videoCodec = VideoCodec.H264)
    val withAudio = EditComposition(tracks = listOf(Track(listOf(Clip(source("a"))))), audio = AudioSpec.Keep)
    assertIs<Verdict.Capable>(lower(withAudio, spec).verdict)

    val withoutAudio = EditComposition(tracks = listOf(Track(listOf(Clip(source("a"))))), audio = AudioSpec.Remove)
    assertIs<Verdict.Capable>(lower(withoutAudio, spec).verdict)
  }

  @Test
  fun audioNonePassesEvenWithAudioSpec() {
    val composition = EditComposition(tracks = listOf(Track(listOf(Clip(source("a"))))), audio = AudioSpec.Keep)
    val spec = ExportSpec(audioCodec = AudioCodec.None)
    assertIs<Verdict.Capable>(lower(composition, spec).verdict)
  }

  @Test
  fun pathSourceIsUnreadable() {
    val composition = EditComposition(tracks = listOf(Track(listOf(Clip(MediaSource.Path("/tmp/clip.mp4"))))))
    val verdict = lower(composition, infos = emptyMap()).verdict
    assertIs<Verdict.Incapable>(verdict)
    assertTrue(verdict.reasons.any { it is ExportError.SourceUnreadable })
  }

  @Test
  fun rotatedSourceIsRefused() {
    val clip = Clip(source("a"))
    val verdict = lower(compositionOf(listOf(clip)), infos = mapOf(clip.source to info(rotation = 90))).verdict
    assertIs<Verdict.Incapable>(verdict)
    assertTrue(verdict.reasons.any { it.message.contains("rotation") })
  }

  @Test
  fun oddSizeIsEvenedAndReported() {
    val clip = Clip(source("a"))
    val verdict = lower(compositionOf(listOf(clip)), infos = mapOf(clip.source to info(1921, 1081))).verdict
    assertIs<Verdict.Degraded>(verdict)
    assertEquals(Size(1920, 1080), verdict.plan.output.size)
    assertTrue(verdict.adjustments.any { it.kind == AdjustmentKind.ResolutionClamped })
  }

  // Every export here is a real decode-draw-encode pass, so a portrait clip is encoded at its own
  // portrait size rather than turned into a landscape encode the container has to correct.
  @Test
  fun portraitOutputKeepsItsPortraitSize() {
    val clip = Clip(source("a"))
    val verdict = lower(compositionOf(listOf(clip)), infos = mapOf(clip.source to info(1080, 1920))).verdict

    assertEquals(Size(1080, 1920), assertIs<Verdict.Capable>(verdict).plan.output.size)
  }

  @Test
  fun targetHeightScalesOutput() {
    val verdict = lower(compositionOf(listOf(Clip(source("a")))), spec = videoSpec(targetHeight = 360)).verdict
    assertIs<Verdict.Capable>(verdict)
    assertEquals(Size(640, 360), verdict.plan.output.size)
  }

  @Test
  fun unsupportedEffectRefusesWithFallback() {
    val composition = compositionOf(listOf(Clip(source("a"))), effects = listOf(Rotate(90)))
    val verdict = lower(composition).verdict
    assertIs<Verdict.Incapable>(verdict)
    assertTrue(verdict.reasons.any { it is ExportError.UnsupportedEffect })
    val fallback = assertNotNull(verdict.withoutUnsupported)
    assertEquals(emptyList(), fallback.effectOrder)
  }

  @Test
  fun supportedEffectsAreExact() {
    val effects: List<EffectSpec> =
      listOf(Crop(AspectRatio(16, 9), Fit.Crop), Flip(FlipAxis.Horizontal), Brightness(0.5f))
    val verdict = lower(compositionOf(listOf(Clip(source("a"))), effects = effects)).verdict
    assertIs<Verdict.Capable>(verdict)
    assertEquals(EffectParity.Exact, verdict.plan.parity)
    assertEquals(3, verdict.plan.effectOrder.size)
    val render = assertNotNull(lower(compositionOf(listOf(Clip(source("a"))), effects = effects)).render)
    assertTrue(render.clips.single().brightness < 1f)
  }

  // Composition geometry resolves against the frame clip geometry leaves behind, not the frame it
  // produces. A composition-level Crop measuring its own output against itself would see no aspect
  // mismatch to crop away.
  @Test
  fun compositionCropChangesTheTextureMatrix() {
    val effects: List<EffectSpec> = listOf(Crop(AspectRatio.Square, Fit.Crop))
    val composition = compositionOf(listOf(Clip(source("a"))), effects = effects)

    val render = assertNotNull(lower(composition).render)

    assertFalse(
      render.clips
        .single()
        .matrix
        .contentEquals(IDENTITY_MATRIX),
    )
  }

  @Test
  fun missingCodecFallsBack() {
    val spec = ExportSpec(videoCodec = VideoCodec.Hevc, audioCodec = AudioCodec.None)
    val verdict = lower(compositionOf(listOf(Clip(source("a")))), spec).verdict
    assertIs<Verdict.Degraded>(verdict)
    assertEquals(VideoCodec.H264, verdict.plan.output.videoCodec)
    assertTrue(verdict.adjustments.any { it.kind == AdjustmentKind.CodecFallback })
  }

  // Proves this backend's own registered ladder, BROWSER_LADDER, not a copy of it: a device
  // without H264 has to fall through to Vp9 before Hevc, so Auto resolving to Vp9 here means the
  // real ladder the planner and this assertion share is still ordered [H264, Vp9, Hevc].
  @Test
  fun autoWalksThisBackendsOwnLadderToTheEncoderThatIsActuallyThere() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(videoCodec = VideoCodec.Auto, audioCodec = AudioCodec.None)
    val vp9AndHevcOnly = device(VideoCodec.Vp9, VideoCodec.Hevc)
    val verdict = planner.lower(compositionOf(listOf(clip)), spec, vp9AndHevcOnly, mapOf(clip.source to info())).verdict
    assertEquals(VideoCodec.Vp9, assertIs<Verdict.Capable>(verdict).plan.output.videoCodec)
  }

  @Test
  fun strictMissingCodecRefuses() {
    val spec = ExportSpec(videoCodec = VideoCodec.Hevc, audioCodec = AudioCodec.None, strict = true)
    val verdict = lower(compositionOf(listOf(Clip(source("a")))), spec).verdict
    assertIs<Verdict.Incapable>(verdict)
    assertTrue(verdict.reasons.any { it is ExportError.NoEncoder })
  }

  @Test
  fun trimIsAlwaysPrecise() {
    val clip = Clip(source("a"), trim = TimeRange(100.milliseconds, 1000.milliseconds))
    val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, trim = TrimStrategy.Fast)
    val verdict = lower(compositionOf(listOf(clip)), spec).verdict
    assertIs<Verdict.Degraded>(verdict)
    assertTrue(verdict.adjustments.any { it.kind == AdjustmentKind.TrimStrategyChanged })
    assertEquals(900.milliseconds, verdict.plan.composition.duration)
  }

  // An HDR source goes through the same HdrMode negotiation media3 and AVFoundation use, so it is
  // never drawn and encoded as if it were SDR with nothing in the verdict saying so.
  @Test
  fun keepingHdrOnASourceThatCarriesItIsRefusedRatherThanSilentlyMisencoded() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
    val verdict =
      lower(
        compositionOf(listOf(clip)),
        spec,
        infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg)),
      ).verdict
    assertIs<Verdict.Incapable>(verdict)
  }

  // The single WebGL pass has no tone-map stage, so a source it can neither encode nor copy has
  // nowhere to go. Refusing is the honest answer. Claiming a tone map it never runs is not.
  @Test
  fun hdrIsRefusedByDefaultWhenItCanNeitherEncodeNorCopy() {
    val clip = Clip(source("a"))
    val verdict = lower(compositionOf(listOf(clip)), infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg))).verdict
    assertIs<Verdict.Incapable>(verdict)
  }

  // An untouched clip needs no WebGL pass at all, so mediabunny's packet copy carries HDR through
  // exactly whatever this backend's own encoder can do, which is the case the tone-map refusal above
  // does not cover.
  @Test
  fun anUntouchedHdrClipTransmuxesRatherThanBeingRefused() {
    val clip = Clip(source("a"))
    val composition = EditComposition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg, codec = "hvc1"))

    val lowering = lower(composition, ExportSpec(), infos)

    val plan = assertIs<Verdict.Capable>(lowering.verdict).plan
    assertEquals(ExportPath.Transmux, plan.path)
    // The copy carries the source's own grade, and the render says which one so the muxer tags it
    // rather than guessing. Claiming Capable alone would pass just as well for a silent SDR write.
    assertEquals(HdrTransfer.Hlg, assertNotNull(lowering.render).hdrTransfer)
    assertNull(lowering.render?.encoderCodec, "a copy opens no encoder, so it names no codec string")
  }

  // There is no browser encoder for HEVC Main10, so an HDR export that has to encode pins the
  // ladder to VP9 Profile 2 rather than the HEVC the mobile backends use. The source carries a
  // codec this backend cannot copy, which is what forces the encode this test is actually about.
  @Test
  fun keepingHdrPinsTheCodecToVp9WhenTheDeviceCanEncodeIt() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(hdr = HdrMode.KeepHdr)
    val device = device(VideoCodec.H264, VideoCodec.Vp9, VideoCodec.Hevc, supportsHdrEncoding = true)
    val composition = EditComposition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos = mapOf(clip.source to info(hdr = HdrTransfer.Hlg, codec = "mpeg2video"))

    val lowering = lower(composition, spec, infos, device)

    val plan = assertIs<Verdict.Capable>(lowering.verdict).plan
    assertEquals(ExportPath.Transcode, plan.path)
    assertEquals(VideoCodec.Vp9, plan.output.videoCodec)
    assertEquals(HdrTransfer.Hlg, assertNotNull(lowering.render).hdrTransfer)
    // The codec alone does not settle the bit depth: VP9 Profile 0 is 8-bit and would write an SDR
    // stream into a file tagged BT.2020.
    assertEquals(HDR_VP9_CODEC, lowering.render?.encoderCodec)
  }

  // ffmpeg and the mobile backends stream-copy an untouched clip straight through. This pipeline
  // now can too: mediabunny reads the source's packets and writes them into a new container with
  // no decode, draw or encode step, so a request for exactly what the source already is claims it.
  @Test
  fun untouchedCompositionClaimsPassthrough() {
    val composition =
      EditComposition(tracks = listOf(Track(listOf(Clip(source("a"))))), audio = AudioSpec.Keep)
    val verdict = lower(composition, ExportSpec()).verdict
    assertEquals(true, assertIs<Verdict.Capable>(verdict).plan.estimate.isPassthrough)
  }

  // mediabunny's writer carries VP8 and Vorbis into mp4, which is more than the shared mp4 baseline
  // the other backends read, so the divergence is this backend's to claim and to prove.
  @Test
  fun anUntouchedVp8ClipCopiesBecauseMediabunnyCarriesIt() {
    val clip = Clip(source("a"))
    val composition = EditComposition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
    val infos =
      mapOf(clip.source to info(codec = "vp8", audio = AudioTrackInfo(trackCodecOf("vorbis"), 48_000, 2, null)))

    val verdict = lower(composition, ExportSpec(), infos).verdict

    assertEquals(ExportPath.Transmux, assertIs<Verdict.Capable>(verdict).plan.path)
  }

  // On a copy, ExportPlanner reports the source's own codec in output.videoCodec rather than the
  // ladder's pick, and VideoCodec has entries webCodecString/muxCodecKey never encode. Planning
  // must succeed rather than throw
  // building fields an encode never runs, and render.container has to say so rather than disagree
  // with the mp4 a copy actually writes.
  @Test
  fun anUntouchedVp8OrAv1SourcePlansWithoutThrowing() {
    for ((codecName, expected) in listOf("vp8" to VideoCodec.Vp8, "av01" to VideoCodec.Av1)) {
      val clip = Clip(source("a"))
      val composition = EditComposition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep)
      val lowering = lower(composition, ExportSpec(), infos = mapOf(clip.source to info(codec = codecName)))

      val plan = assertIs<Verdict.Capable>(lowering.verdict).plan
      assertEquals(expected, plan.output.videoCodec, "codec $codecName")
      assertNull(assertNotNull(lowering.render).container, "codec $codecName")
    }
  }

  // canCopy refusing a codec falls back to a normal transcode rather than a refusal or a broken
  // copy. Audio is off here to isolate that fallback from anything about audio.
  @Test
  fun uncopyableCodecStillPlansTranscode() {
    val clip = Clip(source("a"))
    val spec = ExportSpec(videoCodec = VideoCodec.Auto, audioCodec = AudioCodec.None)
    val verdict = lower(compositionOf(listOf(clip)), spec, infos = mapOf(clip.source to info(codec = "theora"))).verdict
    assertEquals(false, assertIs<Verdict.Capable>(verdict).plan.estimate.isPassthrough)
  }

  // AudioSpec.AudioOnly writes a file with an audio track and nothing else. The plan has to say so
  // here, or the pipeline opens a video encoder and draws frames the caller never asked for.
  @Test
  fun audioOnlyPlansNothingToDraw() {
    val clip = Clip(source("a"))
    val composition = EditComposition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.AudioOnly)
    val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
    val lowering =
      planner.lower(
        composition,
        spec,
        device(VideoCodec.H264, audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2))),
        mapOf(clip.source to info(audio = AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null))),
      )

    assertIs<Verdict.Capable>(lowering.verdict)
    val render = assertNotNull(lowering.render)
    assertEquals(false, render.writesVideo)
    assertEquals(emptyList(), render.clips)
    assertNull(render.encoderCodec)
    assertNull(render.muxCodec)
    assertNull(render.container)
    assertEquals(0L, render.estimatedFrames)
    assertEquals(48_000, assertNotNull(render.audioFormat).sampleRate)
  }

  // The mixer renders in windows, so a long timeline costs a window at a time and there is nothing
  // left for the planner to refuse it over.
  @Test
  fun aLongMixIsPlannedRatherThanRefused() {
    val lowering = mixExport()

    assertIs<Verdict.Capable>(lowering.verdict)
    assertEquals(48_000, assertNotNull(assertNotNull(lowering.render).audioFormat).sampleRate)
  }

  @Test
  fun aLongCopyStillCopies() {
    val lowering = mixExport(spec = ExportSpec())

    assertIs<Verdict.Capable>(lowering.verdict)
    assertNull(assertNotNull(lowering.render).encoderCodec)
  }

  private fun transcodeSpec(audioCodec: AudioCodec): ExportSpec =
    ExportSpec(targetHeight = 720, videoCodec = VideoCodec.H264, audioCodec = audioCodec)

  // One clip of a source that carries audio, running well past a single mix window by default.
  private fun mixExport(
    spec: ExportSpec = transcodeSpec(AudioCodec.Aac),
    duration: Duration = 30.minutes,
    sampleRate: Int = 48_000,
    channelCount: Int = 2,
  ): BrowserLowering {
    val clip = Clip(source("a"))
    return planner.lower(
      composition = EditComposition(tracks = listOf(Track(listOf(clip))), audio = AudioSpec.Keep),
      spec = spec,
      device = device(VideoCodec.H264, audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2))),
      infos =
        mapOf(
          clip.source to
            info(
              duration = duration,
              audio = AudioTrackInfo(trackCodecOf("mp4a"), sampleRate, channelCount, null),
            ),
        ),
    )
  }

  @Test
  fun secondVideoTrackIsRefused() {
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(source("a")))), Track(listOf(Clip(source("b"))))),
        audio = AudioSpec.Remove,
      )
    assertIs<Verdict.Incapable>(lower(composition).verdict)
  }

  @Test
  fun vp9GoesToWebm() {
    val spec = ExportSpec(videoCodec = VideoCodec.Vp9, audioCodec = AudioCodec.None)
    val lowering = lower(compositionOf(listOf(Clip(source("a")))), spec)
    assertIs<Verdict.Capable>(lowering.verdict)
    assertEquals("webm", assertNotNull(lowering.render).container)
  }

  @Test
  fun parityIsExactForSupportedAndNullForTheRest() {
    val prober = MediaProber { ProbeResult.Failure(ExportError.SourceUnreadable("", "unused by this test")) }
    val engine = BrowserExportEngine(ComponentRegistry.Builder().build(), prober)
    assertEquals(EffectParity.Exact, engine.parityOf(EffectIds.CROP))
    assertEquals(EffectParity.Exact, engine.parityOf(EffectIds.BRIGHTNESS))
    assertNull(engine.parityOf(EffectIds.ROTATE))
  }

  private fun compositionOf(
    clips: List<Clip>,
    effects: List<EffectSpec> = emptyList(),
  ): EditComposition =
    EditComposition(
      tracks = listOf(Track(clips = clips)),
      effects = effects,
      audio = AudioSpec.Remove,
    )

  private fun source(name: String): MediaSource = MediaSource.Bytes(name.encodeToByteArray())

  private fun videoSpec(targetHeight: Int? = null): ExportSpec =
    ExportSpec(targetHeight = targetHeight, videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None)

  private fun info(
    width: Int = 1920,
    height: Int = 1080,
    rotation: Int = 0,
    duration: Duration = 2000.milliseconds,
    hdr: HdrTransfer? = null,
    codec: String = "avc1",
    audio: AudioTrackInfo? = null,
  ): MediaInfo =
    MediaInfo(
      duration = duration,
      video =
        VideoTrackInfo(
          codedSize = Size(width, height),
          displaySize = Size(width, height),
          rotationDegrees = rotation,
          pixelAspectRatio = 1f,
          frameRate = 30f,
          codec = trackCodecOf(codec),
          bitDepth = if (hdr == null) 8 else 10,
          colorSpace = ColorSpace.Bt709,
          hdrTransfer = hdr,
          bitrate = null,
        ),
      audio = audio,
      isExportable = true,
    )

  private fun device(
    vararg codecs: VideoCodec,
    audio: List<AudioEncoderCapability> = emptyList(),
    supportsHdrEncoding: Boolean = false,
  ): DeviceCapabilities =
    DeviceCapabilities(
      video = codecs.map { VideoEncoderCapability(it, null, Size(3840, 2160), null, null, false, 2) },
      audio = audio,
      supportsHdrEncoding = supportsHdrEncoding,
      concurrentSessionBudget = null,
    )

  private companion object {
    val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
  }
}
