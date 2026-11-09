package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import dev.jordond.filmstrip.transform.internal.ExportGrade
import dev.jordond.filmstrip.transform.internal.ExportPlanner
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * A negotiation handed its grade writes no file, so a missing encoder refuses nothing there, and an export still
 * refuses or drops what it has no encoder for.
 */
class NoEncoderNegotiationTest {
  private val fixture = PlannerFixture()
  private val noVideo = fixture.device(video = emptyList())
  private val nothing = fixture.device(video = emptyList(), audio = emptyList())

  private fun planner(): ExportPlanner = fixture.planner(muxerAccepts = { false }, ladder = LADDER)

  private fun graded(
    edit: Composition,
    device: DeviceCapabilities,
    grade: ResolvedHdr = ResolvedHdr.Keep,
    spec: ExportSpec = ExportSpec(),
  ): ResolvedComposition =
    planner()
      .negotiate(edit, Ask(spec, device, fixture.infos(edit), grade = grade))
      .composition
      .shouldNotBeNull()

  private fun exported(
    edit: Composition,
    device: DeviceCapabilities,
    spec: ExportSpec = ExportSpec(),
  ): Verdict = planner().negotiate(edit, Ask(spec, device, fixture.infos(edit))).verdict

  private fun clip(
    hdr: HdrTransfer? = null,
    audioRate: Int? = null,
  ): Clip = fixture.clip(hdr = hdr, audioRate = audioRate)

  @Test
  fun `a graded negotiation on a device with no video encoder resolves to the ladder's codec`() {
    for ((hdr, grade) in GRADES) {
      val resolved = graded(composition(clip(hdr)), noVideo, grade)
      val encodesGrade = hdr != null && grade == ResolvedHdr.Keep

      resolved.path shouldBe ExportPath.Transcode
      resolved.output.videoCodec shouldBe if (encodesGrade) DEFAULT_HDR_LADDER.first() else LADDER.first()
      resolved.encoderName.shouldBeNull()
      resolved.hdr shouldBe grade
      resolved.adjustments.filter { it.kind == AdjustmentKind.CodecFallback }.shouldBeEmpty()
    }
  }

  // The source would copy, which is how the export keeps its grade, and only the cap puts it on the encoder path.
  @Test
  fun `a capped preview of a copied grade reads the HDR ladder with no encoder for it`() {
    val edit = composition(clip(HdrTransfer.Pq))
    val h264Only = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264)))
    val capped = ExportSpec(heightPx = 720)

    for (device in listOf(noVideo, h264Only)) {
      val resolved =
        fixture
          .planner(ladder = LADDER)
          .negotiate(edit, Ask(capped, device, fixture.infos(edit), grade = ResolvedHdr.Keep))
          .composition
          .shouldNotBeNull()

      resolved.path shouldBe ExportPath.Transcode
      resolved.hdrTransfer shouldBe HdrTransfer.Pq
      resolved.output.videoCodec shouldBe DEFAULT_HDR_LADDER.first()
      resolved.encoderName.shouldBeNull()
      resolved.output.size.height shouldBe capped.heightPx
    }
  }

  @Test
  fun `the frame of an encoderless negotiation is the source's with no ceiling on it`() {
    val resolved = graded(composition(fixture.clip(size = OVER_ANY_ENCODER)), noVideo)

    resolved.output.size shouldBe OVER_ANY_ENCODER
  }

  @Test
  fun `an export with no video encoder still refuses by naming the missing encoder`() {
    for ((hdr, _) in GRADES) {
      val edit = composition(clip(hdr))

      exported(edit, noVideo)
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()
        .shouldBeInstanceOf<FilmstripError.NoEncoder>()
        .codec shouldBe VideoCodec.Auto
    }
  }

  // The device holds a codec, just not one this engine's ladder tries.
  @Test
  fun `an encoder off the ladder never refuses a graded negotiation and still refuses an export`() {
    val offLadder = fixture.device(video = listOf(fixture.encoder(VideoCodec.Vp9)))
    val edit = composition(clip())

    val resolved = graded(edit, offLadder)

    resolved.output.videoCodec shouldBe LADDER.first()
    resolved.encoderName.shouldBeNull()
    exported(edit, offLadder).shouldBeInstanceOf<Verdict.Incapable>()
  }

  @Test
  fun `a device holding only an unasked codec falls back the same with or without a grade`() {
    val hevcOnly = fixture.device(video = listOf(fixture.encoder(VideoCodec.Hevc)))
    val spec = ExportSpec(videoCodec = VideoCodec.H264)
    val edit = composition(clip())

    val resolved = graded(edit, hevcOnly, spec = spec)
    val verdict = exported(edit, hevcOnly, spec).shouldBeInstanceOf<Verdict.Degraded>()

    resolved.output.videoCodec shouldBe VideoCodec.Hevc
    verdict.plan.output.videoCodec shouldBe VideoCodec.Hevc
    resolved.adjustments.map { it.kind } shouldBe listOf(AdjustmentKind.CodecFallback)
    resolved.adjustments shouldBe verdict.adjustments
  }

  @Test
  fun `a graded negotiation with no encoder at all keeps the audio at the source's rate`() {
    val output = graded(composition(clip(audioRate = AUDIO_RATE)), nothing).output

    output.audioFormat shouldBe AudioFormat(AUDIO_RATE, 2)
    output.audioCodec shouldBe AudioCodec.Auto
  }

  // An export runs on this device and writes no audio, so its preview has none either.
  @Test
  fun `a graded negotiation with a video encoder and no audio encoder stays as silent as its export`() {
    val edit = composition(clip(audioRate = AUDIO_RATE))
    val silent = fixture.device(audio = emptyList())
    val spec = ExportSpec(audioCodec = AudioCodec.Aac)

    val output = graded(edit, silent, spec = spec).output
    val plan = exported(edit, silent, spec).shouldBeInstanceOf<Verdict.Capable>().plan

    output.audioCodec shouldBe AudioCodec.None
    output.audioFormat.shouldBeNull()
    output.audioCodec shouldBe plan.output.audioCodec
    output.audioFormat shouldBe plan.output.audioFormat
  }

  @Test
  fun `an export with no audio encoder still writes no audio`() {
    val edit = composition(clip(audioRate = AUDIO_RATE))

    val output =
      exported(edit, fixture.device(audio = emptyList()))
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.output

    output.audioCodec shouldBe AudioCodec.None
    output.audioFormat.shouldBeNull()
  }

  @Test
  fun `a graded negotiation with nothing to hear stays silent`() {
    val removed = composition(clip(audioRate = AUDIO_RATE)).withAudio(AudioSpec.Remove)
    val noTrack = composition(clip())
    val namedNone = composition(clip(audioRate = AUDIO_RATE))

    listOf(
      graded(removed, nothing),
      graded(noTrack, nothing),
      graded(namedNone, nothing, spec = ExportSpec(audioCodec = AudioCodec.None)),
    ).forEach { resolved ->
      resolved.output.audioCodec shouldBe AudioCodec.None
      resolved.output.audioFormat.shouldBeNull()
    }
  }

  @Test
  fun `a device with an audio encoder reads the same with or without a grade`() {
    val edit = composition(clip(audioRate = AUDIO_RATE))

    val resolved = graded(edit, fixture.device())
    val plan = exported(edit, fixture.device()).shouldBeInstanceOf<Verdict.Capable>().plan

    resolved.output.audioCodec shouldBe plan.output.audioCodec
    resolved.output.audioFormat shouldBe plan.output.audioFormat
  }

  // The preview reads its grade off the export, so it refuses here before it ever reaches the encoder.
  @Test
  fun `a required HDR grade with no HDR encoder is refused on the preview's read in the export's words`() {
    val edit = composition(clip(HdrTransfer.Pq))
    val keep = ExportSpec(hdr = HdrMode.KeepHdr, bitrate = Bitrate.mbps(8))
    val ask = Ask(keep, nothing, fixture.infos(edit))

    val refusal = planner().exportGradeOf(edit, ask).shouldBeInstanceOf<ExportGrade.Refused>().error

    refusal shouldBe
      exported(edit, nothing, keep)
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()
  }

  private companion object {
    val LADDER = listOf(VideoCodec.H264, VideoCodec.Hevc)
    val OVER_ANY_ENCODER = Size(8192, 4320)
    val GRADES =
      listOf(
        null to ResolvedHdr.Keep,
        HdrTransfer.Pq to ResolvedHdr.Keep,
        HdrTransfer.Pq to ResolvedHdr.ToneMap,
      )
    const val AUDIO_RATE = 37_800
  }
}
