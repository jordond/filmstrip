package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.ExecutionContext
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.Crop
import dev.jordond.filmstrip.effects.CropRect
import dev.jordond.filmstrip.effects.Rotate
import dev.jordond.filmstrip.effects.Scale
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.TrimStrategy
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The lowering is pure, so it runs on the host with fabricated sources and a resolver that hands
// back opaque handles. What media3 does with those handles is a device test.
class ExportPlannerTest {
  @Test
  fun `an untouched single clip plans capable and transmuxes`() {
    val verdict = plan(composition(clip(audioRate = 44_100)))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.output.size shouldBe Size(1920, 1080)
    plan.output.videoCodec shouldBe VideoCodec.H264
    plan.output.audioCodec shouldBe AudioCodec.Aac
    plan.path shouldBe ExportPath.Transmux
    plan.estimate.isPassthrough shouldBe true
  }

  @Test
  fun `an untouched clip transmuxes when no canCopy is supplied`() {
    val verdict = plan(composition(clip()))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transmux
  }

  // The ladder's first pick for Auto is H264, and this source is HEVC, so a plan that reported the
  // ladder rather than the source would pass this by coincidence.
  @Test
  fun `a copy reports the source's real codec rather than the ladder's pick`() {
    val verdict = plan(composition(clip(codec = "hvc1")))

    assertIs<Verdict.Capable>(verdict).plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  // A permissive canCopy agrees to copy anything the muxer takes, which is a wider set than the
  // codecs VideoCodec and AudioCodec have members for. Transcoding instead of naming the wrong
  // thing is what keeps the plan honest when a backend's own canCopy does not narrow this itself.
  @Test
  fun `a source whose codec neither enum can name transcodes rather than crashing`() {
    val verdict = plan(composition(clip(codec = "some-future-codec")))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a copy reports the source's real sample rate and channel count rather than encoder-snapped values`() {
    val device = device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 1)))
    val verdict = plan(composition(clip(audioRate = 37_800)), device = device)

    val format = assertIs<Verdict.Capable>(verdict).plan.output.audioFormat
    format?.sampleRate shouldBe 37_800
    format?.channelCount shouldBe 2
  }

  @Test
  fun `a canCopy refusal on an otherwise untouched clip transcodes`() {
    val verdict = plan(composition(clip()), canCopy = { false })

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a bitrate target on an otherwise untouched clip transcodes`() {
    val verdict = plan(composition(clip()), ExportSpec(bitrate = Bitrate.mbps(2)))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a requested audio codec on an otherwise untouched clip transcodes`() {
    val verdict = plan(composition(clip()), ExportSpec(audioCodec = AudioCodec.Aac))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a track with a lead-in transcodes`() {
    val verdict = plan(EditComposition(listOf(Track(listOf(clip()), start = 2_000.milliseconds))))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a muted clip on an otherwise untouched clip transcodes`() {
    val verdict = plan(composition(clip().withAudio(AudioLevel.Mute)))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a track volume level on an otherwise untouched clip transcodes`() {
    val verdict = plan(EditComposition(listOf(Track(listOf(clip()), audio = AudioLevel.Volume(0.5f)))))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a video-only track on an otherwise untouched clip transcodes`() {
    val verdict = plan(EditComposition(listOf(Track(listOf(clip()), content = TrackContent.Video))))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  // A copy would already have kept this untouched clip's grade with no tone map at all, so the
  // canCopy refusal below is what actually forces the tone map this test is about.
  @Test
  fun `a tone-mapped hdr source that cannot copy transcodes`() {
    val verdict = plan(composition(clip(hdr = HdrTransfer.Hlg)), canCopy = { false })

    assertIs<Verdict.Degraded>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  // The cheapest fix for HDR on a device with no HDR encoder: an untouched clip needs no encoder at
  // all, so a copy keeps the grade exactly whatever the device can do.
  @Test
  fun `an untouched hdr clip transmuxes when the device can neither encode nor tone-map it`() {
    val verdict = plan(composition(clip(hdr = HdrTransfer.Hlg)), canToneMap = false)

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
  }

  @Test
  fun `an untouched hdr clip keeps its grade through the copy`() {
    val resolved = resolve(composition(clip(hdr = HdrTransfer.Pq)), canToneMap = false)

    resolved.hdr shouldBe ResolvedHdr.Keep
  }

  // Test the middle of the range a copy can cover, not just its ends: any one of an effect, a trim
  // or a size change is enough on its own to take the clip off the copy path.
  @Test
  fun `an hdr clip with an effect still refuses when it can neither encode nor tone-map`() {
    val verdict = plan(composition(clip(hdr = HdrTransfer.Hlg, effects = listOf(Rotate(90)))), canToneMap = false)

    assertIs<Verdict.Incapable>(verdict)
  }

  @Test
  fun `a trimmed hdr clip still refuses when it can neither encode nor tone-map`() {
    val trimmed = clip(hdr = HdrTransfer.Hlg, trim = TimeRange.of(0.milliseconds, 3_000.milliseconds))
    val verdict = plan(composition(trimmed), canToneMap = false)

    assertIs<Verdict.Incapable>(verdict)
  }

  @Test
  fun `a resized hdr clip still refuses when it can neither encode nor tone-map`() {
    val verdict = plan(composition(clip(hdr = HdrTransfer.Hlg)), ExportSpec(targetHeight = 720), canToneMap = false)

    assertIs<Verdict.Incapable>(verdict)
  }

  @Test
  fun `a target height keeps the source aspect`() {
    val verdict = plan(composition(clip()), ExportSpec(targetHeight = 720))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.output.size shouldBe Size(1280, 720)
    plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `an odd frame rounds to the encoder's alignment and says so`() {
    val verdict = plan(composition(clip(size = Size(1921, 1081))), canCopy = { false })

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.path shouldBe ExportPath.Transcode
    degraded.plan.output.size shouldBe Size(1920, 1080)
    degraded.adjustments.single().kind shouldBe AdjustmentKind.ResolutionClamped
  }

  // An encoder's alignment is the encoder's problem, and a copy runs none, so the odd frame goes
  // across whole rather than being reported as a clamp that never happens.
  @Test
  fun `an odd frame copies whole rather than rounding when it can transmux`() {
    val verdict = plan(composition(clip(size = Size(1921, 1081))))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
    plan.output.size shouldBe Size(1921, 1081)
  }

  @Test
  fun `a frame larger than any encoder accepts still copies at its own size`() {
    val small = encoder(VideoCodec.H264, maxSize = Size(1280, 720))
    val verdict = plan(composition(clip(size = Size(3840, 2160))), device = device(video = listOf(small)))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
    plan.output.size shouldBe Size(3840, 2160)
  }

  @Test
  fun `a frame larger than the encoder accepts is clamped to it and reported`() {
    val small = encoder(VideoCodec.H264, maxSize = Size(1280, 720))
    val verdict =
      plan(composition(clip(size = Size(3840, 2160))), device = device(video = listOf(small)), canCopy = { false })

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.size shouldBe Size(1280, 720)
    degraded.adjustments.single().kind shouldBe AdjustmentKind.ResolutionClamped
  }

  @Test
  fun `strict refuses a clamped frame by size`() {
    val small = encoder(VideoCodec.H264, maxSize = Size(1280, 720))
    val verdict =
      plan(
        composition(clip(size = Size(3840, 2160))),
        ExportSpec(strict = true),
        device = device(video = listOf(small)),
        canCopy = { false },
      )

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.UnsupportedResolution>(error).max shouldBe Size(1280, 720)
  }

  // strict says the numbers were not negotiable, and a copy negotiates none of them: the frame goes
  // across at its own size, so there is no clamp to refuse.
  @Test
  fun `strict still transmuxes an oversized frame that copies untouched`() {
    val small = encoder(VideoCodec.H264, maxSize = Size(1280, 720))
    val verdict =
      plan(
        composition(clip(size = Size(3840, 2160))),
        ExportSpec(strict = true),
        device = device(video = listOf(small)),
      )

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
    plan.output.size shouldBe Size(3840, 2160)
  }

  @Test
  fun `a portrait source keeps its portrait output size`() {
    val verdict = plan(composition(clip(size = Size(1080, 1920))))

    assertIs<Verdict.Capable>(verdict).plan.output.size shouldBe Size(1080, 1920)
  }

  @Test
  fun `a second video track is refused by name`() {
    val second = Track(listOf(clip()), content = TrackContent.AudioAndVideo)
    val verdict = plan(EditComposition(listOf(Track(listOf(clip())), second)))

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "This backend renders video from the primary track only. A second video track needs a " +
      "compositor, which has not landed here. A second audio-only track works."
  }

  @Test
  fun `a second audio-only track is accepted and does not bound the composition`() {
    val bed =
      Track(
        listOf(clip(duration = 60_000.milliseconds, audioRate = 44_100)),
        content = TrackContent.Audio,
        looping = true,
      )
    val verdict = plan(EditComposition(listOf(Track(listOf(clip())), bed)))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.output.audioCodec shouldBe AudioCodec.Aac
  }

  @Test
  fun `a composition where every track loops is refused`() {
    val verdict = plan(EditComposition(listOf(Track(listOf(clip()), looping = true))))

    assertIs<Verdict.Incapable>(verdict)
  }

  @Test
  fun `an unclaimed effect is refused by id and offers a plan without it`() {
    val verdict = plan(composition(clip(effects = listOf(Rotate(90)))), resolvers = emptyList())

    val incapable = assertIs<Verdict.Incapable>(verdict)
    assertIs<ExportError.UnsupportedEffect>(incapable.reasons.single()).specId shouldBe Rotate(90).id
    assertNotNull(incapable.withoutUnsupported).effectOrder shouldBe emptyList()
  }

  @Test
  fun `a claimed but refused effect keeps the resolver's message`() {
    val verdict =
      plan(
        composition(clip(effects = listOf(Rotate(90)))),
        resolvers = listOf(FakeResolver(refuse = setOf(Rotate(90).id))),
      )

    val incapable = assertIs<Verdict.Incapable>(verdict)
    incapable.reasons.single().message shouldBe "this resolver owns it and cannot render it"
  }

  @Test
  fun `a rotation swaps the output frame`() {
    val verdict = plan(composition(clip(effects = listOf(Rotate(90)))))

    assertIs<Verdict.Capable>(verdict).plan.output.size shouldBe Size(1080, 1920)
  }

  // 1920x1080 at 480 high is 853.3 wide, and an encoder that wants even sides cannot have 853.
  @Test
  fun `a scale sets the output height and the alignment rounding is reported`() {
    val verdict = plan(composition(clip(effects = listOf(Scale(targetHeight = 480)))))

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.size shouldBe Size(852, 480)
    degraded.adjustments.single().kind shouldBe AdjustmentKind.ResolutionClamped
  }

  // The crop's target aspect equals the output frame's own aspect, so measuring it against the
  // output rather than the frame clip geometry leaves behind would see no aspect mismatch and crop
  // nothing.
  @Test
  fun `composition geometry resolves against the frame clip geometry leaves behind`() {
    val recorder = RecordingResolver()
    val crop = Crop(aspect = AspectRatio.Square, fit = Fit.Crop)
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(clip(effects = listOf(Rotate(90)))))),
        effects = listOf(crop),
      )

    val resolved = resolve(composition, resolvers = listOf(recorder))

    recorder.inputSizes.getValue(crop.id) shouldBe Size(1080, 1920)
    resolved.output.size shouldBe Size(1080, 1080)
  }

  // The middle of the range a composition crop can see: no clip geometry runs first, so the frame
  // it measures against is the source frame itself.
  @Test
  fun `composition geometry resolves against the source frame when there is no clip geometry`() {
    val recorder = RecordingResolver()
    val crop = Crop(aspect = AspectRatio.Classic, fit = Fit.Crop)
    val composition = EditComposition(listOf(Track(listOf(clip()))), effects = listOf(crop))

    val resolved = resolve(composition, resolvers = listOf(recorder))

    recorder.inputSizes.getValue(crop.id) shouldBe Size(1920, 1080)
    resolved.output.size shouldBe Size(1440, 1080)
  }

  @Test
  fun `compositionInputSize is the frame before composition geometry runs and neither the source nor the output`() {
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(clip(effects = listOf(Rotate(90)))))),
        effects = listOf(Crop(aspect = AspectRatio.Square, fit = Fit.Crop)),
      )

    val resolved = resolve(composition, ExportSpec(targetHeight = 540))

    resolved.compositionInputSize shouldBe Size(1080, 1920)
    resolved.output.size shouldBe Size(540, 540)
  }

  // Clip geometry always finishes before the composition's own runs. Sorting a composition-level
  // Rotate ahead of a clip-level Scale by rank alone is an order no backend ever runs, and lands on
  // a different frame.
  @Test
  fun `composition geometry runs after clip geometry rather than by merged rank`() {
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(clip(effects = listOf(Scale(targetHeight = 540)))))),
        effects = listOf(Rotate(90)),
      )

    val verdict = plan(composition)

    assertIs<Verdict.Capable>(verdict).plan.output.size shouldBe Size(540, 960)
  }

  // CropRect resolves eagerly against spec.rect and never reads attributes.inputSize. This pins
  // that it still works once geometry is staged.
  @Test
  fun `a composition-level crop rect still sets the output frame`() {
    val rect = NormalizedRect(left = 0f, top = 0f, right = 0.5f, bottom = 1f)
    val composition = EditComposition(listOf(Track(listOf(clip()))), effects = listOf(CropRect(rect)))

    val verdict = plan(composition)

    assertIs<Verdict.Capable>(verdict).plan.output.size shouldBe Size(960, 1080)
  }

  @Test
  fun `an unavailable codec falls back and reports it`() {
    val verdict = plan(composition(clip()), ExportSpec(videoCodec = VideoCodec.Vp9))

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.videoCodec shouldBe VideoCodec.H264
    degraded.adjustments.single().kind shouldBe AdjustmentKind.CodecFallback
  }

  @Test
  fun `strict refuses the fallback it would otherwise take`() {
    val verdict = plan(composition(clip()), ExportSpec(videoCodec = VideoCodec.Vp9, strict = true))

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.NoEncoder>(error).codec shouldBe VideoCodec.Vp9
  }

  @Test
  fun `a device with no video encoder at all is refused`() {
    val verdict = plan(composition(clip()), device = device(video = emptyList()))

    assertIs<ExportError.NoEncoder>(assertIs<Verdict.Incapable>(verdict).reasons.single())
  }

  @Test
  fun `hdr is tone-mapped when the device can neither encode it nor copy it across`() {
    val verdict = plan(composition(clip(hdr = HdrTransfer.Hlg)), canCopy = { false })

    val degraded = assertIs<Verdict.Degraded>(verdict)
    val adjustment = degraded.adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.HdrToneMapped
    adjustment.message shouldBe "This device cannot encode HDR, so the grade is tone-mapped to SDR."
  }

  @Test
  fun `an asked-for tone map is not an adjustment`() {
    // Auto is the mode that can land somewhere else, so it is the one that reports. Asking for a
    // tone map and getting one is the request honoured, and calling that degraded costs a caller
    // matching on Capable the plan it can run, and strict the export entirely.
    val verdict =
      plan(
        composition(clip(hdr = HdrTransfer.Hlg)),
        ExportSpec(hdr = HdrMode.ToneMapToSdr),
        device = device(hdr = true),
      )

    assertIs<Verdict.Capable>(verdict)
  }

  @Test
  fun `a strict export survives the tone map it asked for`() {
    val verdict =
      plan(
        composition(clip(hdr = HdrTransfer.Hlg)),
        ExportSpec(hdr = HdrMode.ToneMapToSdr, strict = true),
        device = device(hdr = true),
      )

    assertIs<Verdict.Capable>(verdict)
  }

  @Test
  fun `hdr is kept when the device can encode it on the only codec that can carry it`() {
    val verdict = plan(composition(clip(hdr = HdrTransfer.Hlg)), device = device(hdr = true))

    // Not H264, which is what the ladder prefers for everything else. Naming it here would have
    // the platform swap in HEVC anyway and the plan describe a file nobody wrote.
    assertIs<Verdict.Capable>(verdict).plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  @Test
  fun `asking for h264 on an hdr export reports the codec it had to use instead`() {
    val verdict =
      plan(
        composition(clip(hdr = HdrTransfer.Hlg)),
        ExportSpec(videoCodec = VideoCodec.H264, hdr = HdrMode.KeepHdr),
        device = device(hdr = true),
      )

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.videoCodec shouldBe VideoCodec.Hevc
    degraded.adjustments.single().kind shouldBe AdjustmentKind.CodecFallback
  }

  @Test
  fun `keeping hdr on a device that can neither encode it nor copy it across is refused`() {
    val verdict =
      plan(composition(clip(hdr = HdrTransfer.Hlg)), ExportSpec(hdr = HdrMode.KeepHdr), canCopy = { false })

    assertIs<Verdict.Incapable>(verdict)
  }

  // An engine that can neither encode the grade, copy it across, nor bring it down has nowhere to
  // put an HDR source, and writing the untouched grade into an SDR file is the one answer that is
  // always wrong.
  @Test
  fun `an engine that can do none of the three refuses a graded source rather than flattening it`() {
    val composition = composition(clip(hdr = HdrTransfer.Pq))
    val verdict =
      planner(canToneMap = false, canCopy = { false })
        .negotiate(composition, ExportSpec(), device(), infos(composition))
        .verdict

    val reason = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(reason).message shouldBe
      "This source carries an HDR grade and this engine can neither encode it nor tone-map it, so " +
      "there is nowhere to put it."
  }

  @Test
  fun `an sdr source is never tone-mapped whatever was asked for`() {
    // There is no grade to map, and saying otherwise costs the stream copy: a platform told to
    // tone-map has to decode every frame to do it.
    val resolved = resolve(composition(clip()), ExportSpec(hdr = HdrMode.ToneMapToSdr))

    resolved.hdr shouldBe ResolvedHdr.Keep
  }

  @Test
  fun `an sdr source keeps the passthrough it was planned for`() {
    val verdict = plan(composition(clip()), ExportSpec(hdr = HdrMode.ToneMapToSdr))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transmux
  }

  @Test
  fun `removing audio writes no audio track and no audio format`() {
    val verdict = plan(composition(clip()).withAudio(AudioSpec.Remove))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.output.audioCodec shouldBe AudioCodec.None
    assertNull(plan.output.audioFormat)
  }

  // A source with no audio track cannot back the track an encoder or a stream copy would be asked
  // to write, so the plan promises none rather than one a copy could never deliver and an encode
  // would have to invent from silence.
  @Test
  fun `a video-only source is not promised an audio track it has none of`() {
    val verdict = plan(composition(clip()))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.output.audioCodec shouldBe AudioCodec.None
    assertNull(plan.output.audioFormat)
  }

  @Test
  fun `a clip with no audio next to one that has it still resolves an audio codec`() {
    val track = Track(listOf(clip(), clip(audioRate = 44_100)))
    val verdict = plan(EditComposition(listOf(track)))

    assertIs<Verdict.Capable>(verdict).plan.output.audioCodec shouldBe AudioCodec.Aac
  }

  @Test
  fun `audio-only output still plans and the video half of the format goes unwritten`() {
    val verdict = plan(composition(clip(audioRate = 44_100)).withAudio(AudioSpec.AudioOnly))

    assertIs<Verdict.Capable>(verdict).plan.output.audioCodec shouldBe AudioCodec.Aac
  }

  @Test
  fun `an audio-only output over a track that carries no audio is refused`() {
    val track = Track(listOf(clip()), content = TrackContent.Video)
    val verdict = plan(EditComposition(listOf(track)).withAudio(AudioSpec.AudioOnly))

    assertIs<ExportError.InvalidComposition>(assertIs<Verdict.Incapable>(verdict).reasons.single())
  }

  @Test
  fun `an audio-only output over a source with no audio track is refused`() {
    val verdict = plan(composition(clip()).withAudio(AudioSpec.AudioOnly))

    assertIs<Verdict.Incapable>(verdict)
  }

  // A stream copy carries the source's own audio format across untouched, so this is a transcode
  // question and forced off the copy path to ask it.
  @Test
  fun `the source sample rate is kept when the encoder lists it`() {
    val device = device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 2)))
    val verdict = plan(composition(clip(audioRate = 44_100)), device = device, canCopy = { false })

    assertIs<Verdict.Capable>(verdict)
      .plan.output.audioFormat
      ?.sampleRate shouldBe 44_100
  }

  // An encoder handed a rate it cannot write resamples on its own, and the file then disagrees with
  // the plan a caller was shown. Forced off the copy path, since a copy never resamples at all.
  @Test
  fun `a sample rate the encoder refuses snaps to the nearest one it takes`() {
    val device = device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 2)))
    val verdict = plan(composition(clip(audioRate = 37_800)), device = device, canCopy = { false })

    assertIs<Verdict.Capable>(verdict)
      .plan.output.audioFormat
      ?.sampleRate shouldBe 44_100
  }

  // Android codecs routinely publish no rate list at all, and inventing a ceiling there would
  // resample a stream that was fine. Forced off the copy path, since a copy always keeps the
  // source's rate regardless of what an encoder would have done with it.
  @Test
  fun `an encoder that lists no rates is given the source's`() {
    val device = device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, emptyList(), 2)))
    val verdict = plan(composition(clip(audioRate = 37_800)), device = device, canCopy = { false })

    assertIs<Verdict.Capable>(verdict)
      .plan.output.audioFormat
      ?.sampleRate shouldBe 37_800
  }

  // Forced off the copy path, since a copy never remixes and would carry the source's real channel
  // count across regardless of what a mono-only encoder could take.
  @Test
  fun `a mono-only encoder is not asked for a stereo mix`() {
    val device = device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 1)))
    val verdict = plan(composition(clip(audioRate = 48_000)), device = device, canCopy = { false })

    assertIs<Verdict.Capable>(verdict)
      .plan.output.audioFormat
      ?.channelCount shouldBe 1
  }

  // A track effect lowers once per clip, so the same approximation is reached twice and a caller
  // would otherwise be told about it twice.
  @Test
  fun `an approximation reached on every clip is reported once`() {
    val track = Track(listOf(clip(), clip()), effects = listOf(Rotate(90)))
    val verdict =
      plan(EditComposition(listOf(track)), resolvers = listOf(FakeResolver(degrade = setOf(Rotate(90).id))))

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.adjustments.count { it.kind == AdjustmentKind.EffectApproximated } shouldBe 1
  }

  @Test
  fun `levels multiply down the scopes`() {
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(clip().withAudio(AudioLevel.Volume(0.5f))), audio = AudioLevel.Volume(0.5f))),
        audio = AudioSpec.Volume(0.5f),
      )

    resolve(composition)
      .tracks
      .single()
      .clips
      .single()
      .gain shouldBe 0.125f
  }

  @Test
  fun `a mute at any scope silences everything below it`() {
    val composition = EditComposition(listOf(Track(listOf(clip()), audio = AudioLevel.Mute)))

    resolve(composition)
      .tracks
      .single()
      .clips
      .single()
      .gain shouldBe 0f
  }

  @Test
  fun `a trim shortens the composition and reports nothing`() {
    val trimmed = clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds))
    val verdict = plan(composition(trimmed))

    assertIs<Verdict.Capable>(verdict)
    firstClipOf(resolve(composition(trimmed))).startsAtKeyFrame shouldBe false
    resolve(composition(trimmed)).duration shouldBe 2_000.milliseconds
  }

  @Test
  fun `asking for fast trim reports no adjustment and starts on a key frame`() {
    val composition = composition(clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds)))
    val verdict = plan(composition, ExportSpec(trim = TrimStrategy.Fast))

    assertIs<Verdict.Capable>(verdict)
    firstClipOf(resolve(composition, ExportSpec(trim = TrimStrategy.Fast))).startsAtKeyFrame shouldBe true
  }

  private fun firstClipOf(resolved: NegotiatedComposition) =
    resolved.tracks
      .first()
      .clips
      .first()

  @Test
  fun `a trim past the end of the source is clamped to it`() {
    resolve(composition(clip(trim = TimeRange.of(1_000.milliseconds, 60_000.milliseconds)))).duration shouldBe
      5_000.milliseconds
  }

  @Test
  fun `a trim that keeps nothing is refused`() {
    assertIs<Verdict.Incapable>(plan(composition(clip(trim = TimeRange.of(6_000.milliseconds, 6_000.milliseconds)))))
  }

  @Test
  fun `a protected source is refused before anything is decoded`() {
    val verdict = plan(composition(clip(exportable = false)))

    assertIs<ExportError.SourceNotExportable>(assertIs<Verdict.Incapable>(verdict).reasons.single())
  }

  @Test
  fun `a requested frame rate above the encoder's ceiling is clamped`() {
    val capped = encoder(VideoCodec.H264, maxFrameRate = 30)
    val verdict = plan(composition(clip()), ExportSpec(frameRate = 60), device = device(video = listOf(capped)))

    val degraded = assertIs<Verdict.Degraded>(verdict)
    degraded.plan.output.frameRate shouldBe 30
    degraded.adjustments.single().kind shouldBe AdjustmentKind.FrameRateClamped
  }

  @Test
  fun `clips concatenate to one output frame`() {
    val composition = composition(clip(), clip(size = Size(1280, 720)))

    val resolved = resolve(composition)
    resolved.output.size shouldBe Size(1920, 1080)
    resolved.tracks
      .single()
      .clips.size shouldBe 2
    resolved.duration shouldBe 12_000.milliseconds
  }

  @Test
  fun `a non-default fill lands on the negotiated composition`() {
    val fill = Fill.Blurred(radius = 0.1f, dim = 0.5f)
    val resolved = resolve(composition(clip()).withFill(fill))

    resolved.fill shouldBe fill
  }

  @Test
  fun `showsFill is true for a contain fit`() {
    val resolved = resolve(composition(clip(effects = listOf(Scale(targetHeight = 480, fit = Fit.Contain)))))

    resolved.showsFill shouldBe true
  }

  @Test
  fun `showsFill is false for a crop fit`() {
    val resolved = resolve(composition(clip(effects = listOf(Scale(targetHeight = 480, fit = Fit.Crop)))))

    resolved.showsFill shouldBe false
  }

  @Test
  fun `showsFill is false for a stretch fit`() {
    val resolved = resolve(composition(clip(effects = listOf(Scale(targetHeight = 480, fit = Fit.Stretch)))))

    resolved.showsFill shouldBe false
  }

  @Test
  fun `showsFill is true when a track starts after the composition does`() {
    val resolved = resolve(EditComposition(listOf(Track(listOf(clip()), start = 2_000.milliseconds))))

    resolved.showsFill shouldBe true
  }

  @Test
  fun `paintsFillAfterEffects holds back only a colour a bar or a gap would otherwise show`() {
    val red = Fill.Solid(0xFFFF0000.toInt())

    resolve(containComposition(Fit.Contain).withFill(red)).paintsFillAfterEffects shouldBe true
    resolve(containComposition(Fit.Contain).withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe false
    resolve(containComposition(Fit.Crop).withFill(red)).paintsFillAfterEffects shouldBe false
    resolve(containComposition(Fit.Stretch).withFill(red)).paintsFillAfterEffects shouldBe false

    val lateStart = 2_000.milliseconds
    val lateTrack =
      EditComposition(listOf(Track(listOf(clip(effects = listOf(Scale(480, Fit.Crop)))), start = lateStart)))
    resolve(lateTrack.withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe true

    val lateContainedTrack =
      EditComposition(listOf(Track(listOf(clip(effects = listOf(Scale(480, Fit.Contain)))), start = lateStart)))
    resolve(lateContainedTrack.withFill(Fill.Blurred())).paintsFillAfterEffects shouldBe true
  }

  @Test
  fun `derivesFromFrame is true only for a blurred fill`() {
    Fill.Solid(0xFFFF0000.toInt()).derivesFromFrame shouldBe false
    Fill.Blurred().derivesFromFrame shouldBe true
  }

  private fun containComposition(fit: Fit): EditComposition = composition(clip(effects = listOf(Scale(480, fit))))

  @Test
  fun `an hdr source kept as hdr accepts a fill other than black`() {
    val verdict = plan(composition(clip(hdr = HdrTransfer.Hlg)).withFill(Fill.Blurred()), device = device(hdr = true))

    assertIs<Verdict.Capable>(verdict)
  }

  @Test
  fun `an hdr source kept as hdr carries a solid fill through to the negotiated composition`() {
    val fill = Fill.Solid(0xFFFF00FFL.toInt())
    val resolved =
      resolve(composition(clip(hdr = HdrTransfer.Pq)).withFill(fill), device = device(hdr = true))

    resolved.fill shouldBe fill
  }

  // Two clips that disagree about the grade have nothing one transfer describes, so keeping either
  // would tag the other's frames with a grade they do not have.
  @Test
  fun `sources that disagree about the transfer refuse to keep either grade`() {
    val verdict =
      plan(
        composition(clip(hdr = HdrTransfer.Hlg), clip(hdr = HdrTransfer.Pq)),
        ExportSpec(hdr = HdrMode.KeepHdr),
        device = device(hdr = true),
      )

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "These sources do not share one HDR transfer, so no single grade describes the output. Use " +
      "HdrMode.Auto or HdrMode.ToneMapToSdr to bring them down to SDR together."
  }

  // An HDR clip sat beside an SDR one is the same disagreement, and the one people actually build.
  @Test
  fun `an hdr clip beside an sdr clip refuses to keep the grade`() {
    val verdict =
      plan(
        composition(clip(hdr = HdrTransfer.Pq), clip()),
        ExportSpec(hdr = HdrMode.KeepHdr),
        device = device(hdr = true),
      )

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "These sources do not share one HDR transfer, so no single grade describes the output. Use " +
      "HdrMode.Auto or HdrMode.ToneMapToSdr to bring them down to SDR together."
  }

  @Test
  fun `mixed grades tone-map together rather than mislabelling the output`() {
    val verdict = plan(composition(clip(hdr = HdrTransfer.Pq), clip()), device = device(hdr = true))

    val degraded = assertIs<Verdict.Degraded>(verdict)
    val adjustment = degraded.adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.HdrToneMapped
    // This device encodes HDR, so the mix is the whole reason and blaming the encoder would send
    // whoever reads it looking at the wrong thing.
    adjustment.message shouldBe
      "These sources do not share one HDR transfer, so no single grade describes the output and " +
      "they are tone-mapped to SDR together."
  }

  @Test
  fun `a tone-mapped mix writes no transfer into the output`() {
    val resolved = resolve(composition(clip(hdr = HdrTransfer.Pq), clip()), device = device(hdr = true))

    resolved.hdr shouldBe ResolvedHdr.ToneMap
    resolved.hdrTransfer shouldBe null
  }

  // The middle of the range the mix rule covers: more than one clip is not itself a disagreement,
  // so clips that share a transfer still keep it.
  @Test
  fun `two clips that share a transfer keep it`() {
    val resolved =
      resolve(composition(clip(hdr = HdrTransfer.Hlg), clip(hdr = HdrTransfer.Hlg)), device = device(hdr = true))

    resolved.hdr shouldBe ResolvedHdr.Keep
    resolved.hdrTransfer shouldBe HdrTransfer.Hlg
  }

  // A copy runs no encoder, so the encoder's rate ceiling is not a ceiling on anything here and the
  // source's own rate is what the file keeps.
  @Test
  fun `a copy keeps a source rate above the encoder's ceiling`() {
    val capped = encoder(VideoCodec.H264, maxFrameRate = 60)
    val verdict = plan(composition(clip(frameRate = 120f)), device = device(video = listOf(capped)))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
    plan.output.frameRate shouldBe 120
  }

  @Test
  fun `an encode clamps a source rate above the encoder's ceiling`() {
    val capped = encoder(VideoCodec.H264, maxFrameRate = 60)
    val verdict =
      plan(composition(clip(frameRate = 120f)), device = device(video = listOf(capped)), canCopy = { false })

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transcode
    plan.output.frameRate shouldBe 60
  }

  private fun plan(
    composition: EditComposition,
    spec: ExportSpec = ExportSpec(),
    device: DeviceCapabilities = device(),
    resolvers: List<EffectResolver> = listOf(FakeResolver()),
    canToneMap: Boolean = true,
    canCopy: (MediaInfo) -> Boolean = { true },
  ) = planner(resolvers, canToneMap = canToneMap, canCopy = canCopy)
    .negotiate(composition, spec, device, infos(composition))
    .verdict

  private fun resolve(
    composition: EditComposition,
    spec: ExportSpec = ExportSpec(),
    device: DeviceCapabilities = device(),
    resolvers: List<EffectResolver> = listOf(FakeResolver()),
    canToneMap: Boolean = true,
    canCopy: (MediaInfo) -> Boolean = { true },
  ) = assertNotNull(
    planner(resolvers, canToneMap = canToneMap, canCopy = canCopy)
      .negotiate(composition, spec, device, infos(composition))
      .composition,
  )

  private fun planner(
    resolvers: List<EffectResolver> = listOf(FakeResolver()),
    canToneMap: Boolean = true,
    canCopy: (MediaInfo) -> Boolean = { true },
  ) = ExportPlanner(
    resolvers = resolvers,
    renderCapabilities = { size, hdr ->
      RenderCapabilities(
        api = RenderApi.OpenGlEs,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = hdr,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = maxOf(size.width, size.height),
        realtimeBudgetNanos = null,
        features = emptySet(),
      )
    },
    parityOf = { EffectParity.Exact },
    unclaimedMessage = { "nothing claimed $it" },
    ladder = listOf(VideoCodec.H264, VideoCodec.Hevc),
    supportsPassthrough = true,
    canToneMap = canToneMap,
    canCopy = canCopy,
  )

  // Every fabricated clip carries its own source, so the map is keyed the way the planner reads it.
  private fun infos(composition: EditComposition): Map<MediaSource, MediaInfo> =
    composition.tracks
      .flatMap { it.clips }
      .associate { it.source to INFOS.getValue(it.source) }

  private fun composition(vararg clips: Clip) = EditComposition(listOf(Track(clips.toList())))

  private fun clip(
    size: Size = Size(1920, 1080),
    duration: Duration = 6_000.milliseconds,
    trim: TimeRange? = null,
    effects: List<EffectSpec> = emptyList(),
    hdr: HdrTransfer? = null,
    exportable: Boolean = true,
    audioRate: Int? = null,
    codec: String? = null,
    frameRate: Float = 30f,
  ): Clip {
    val source = MediaSource.of("/fixtures/clip-${INFOS.size}.mp4")
    INFOS[source] =
      MediaInfo(
        duration = duration,
        video =
          VideoTrackInfo(
            codedSize = size,
            displaySize = size,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            frameRate = frameRate,
            codec = trackCodecOf(codec ?: if (hdr == null) "avc1" else "hvc1"),
            bitDepth = if (hdr == null) 8 else 10,
            colorSpace = ColorSpace.Bt709,
            hdrTransfer = hdr,
            bitrate = Bitrate.mbps(12),
          ),
        audio = audioRate?.let { AudioTrackInfo(trackCodecOf("mp4a"), it, 2, null) },
        isExportable = exportable,
      )
    return Clip(source, trim, effects)
  }

  private fun device(
    video: List<VideoEncoderCapability> = listOf(encoder(VideoCodec.H264), encoder(VideoCodec.Hevc)),
    audio: List<AudioEncoderCapability> = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2)),
    hdr: Boolean = false,
  ) = DeviceCapabilities(video, audio, hdr, concurrentSessionBudget = null)

  private fun encoder(
    codec: VideoCodec,
    maxSize: Size = Size(3840, 2160),
    maxFrameRate: Int? = null,
  ) = VideoEncoderCapability(
    codec = codec,
    encoderName = null,
    maxSize = maxSize,
    maxFrameRate = maxFrameRate,
    maxBitrate = null,
    isHardwareAccelerated = true,
    sizeAlignment = 2,
  )

  private val INFOS = mutableMapOf<MediaSource, MediaInfo>()
}

/**
 * Hands back opaque handles, so the lowering can be asserted on without media3.
 */
private class FakeResolver(
  private val refuse: Set<String> = emptySet(),
  private val degrade: Set<String> = emptySet(),
) : EffectResolver {
  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    context: ExecutionContext,
    attributes: Attributes,
  ): EffectResolution =
    when (spec.id) {
      in refuse -> {
        EffectResolution.Unsupported(spec.id, "this resolver owns it and cannot render it")
      }
      in degrade -> {
        EffectResolution.Degraded(
          fakePlatformEffect(),
          DegradationReason.ApproximateAlgorithm,
          "this resolver rounds it",
        )
      }
      else -> {
        EffectResolution.Resolved(fakePlatformEffect())
      }
    }
}

/**
 * Hands back opaque handles like [FakeResolver], and keeps the frame each spec was measured
 * against so a plan's geometry chain is assertable.
 */
private class RecordingResolver : EffectResolver {
  val inputSizes = mutableMapOf<String, Size>()
  val outputSizes = mutableMapOf<String, Size>()

  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    context: ExecutionContext,
    attributes: Attributes,
  ): EffectResolution {
    inputSizes[spec.id] = attributes.inputSize
    outputSizes[spec.id] = attributes.outputSize
    return EffectResolution.Resolved(fakePlatformEffect())
  }
}
