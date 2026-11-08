package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * A preview lowered at the grade [ExportPlanner.exportGradeOf] answers resolves to what the export writes, whatever
 * the preview's own frame asks for, and refuses what the export refuses.
 */
class ExportGradeTest {
  private val fixture = PlannerFixture()
  private val noHdrEncoder = fixture.device(hdr = false)
  private val encodesHdr = fixture.device(hdr = true)
  private val needsSdr =
    object : EffectSpec {
      override val id = "test.needsSdr"
      override val requiresSdr = true
    }

  private fun edit(clip: Clip) = EditComposition(listOf(Track(listOf(clip))))

  private fun export(
    edit: EditComposition,
    spec: ExportSpec,
    device: DeviceCapabilities,
  ): ResolvedComposition =
    fixture
      .planner()
      .negotiate(edit, Ask(spec, device, fixture.infos(edit)))
      .composition
      .shouldNotBeNull()

  private fun grade(
    edit: EditComposition,
    spec: ExportSpec,
    device: DeviceCapabilities,
  ): ExportGrade = fixture.planner().exportGradeOf(edit, Ask(spec, device, fixture.infos(edit)))

  private fun preview(
    edit: EditComposition,
    spec: ExportSpec,
    device: DeviceCapabilities,
    frame: ExportSpec = ExportSpec(),
  ): ResolvedComposition =
    fixture
      .planner()
      .negotiate(
        edit,
        Ask(
          frame,
          device,
          fixture.infos(edit),
          grade = grade(edit, spec, device).shouldBeInstanceOf<ExportGrade.Settled>().hdr,
        ),
      ).composition
      .shouldNotBeNull()

  @Test
  fun `an export that names a setting tone maps and a full preview of it tone maps too`() {
    val settings =
      listOf(
        ExportSpec(frameRate = 24),
        ExportSpec(videoCodec = VideoCodec.H264),
        ExportSpec(audioCodec = AudioCodec.Aac),
        ExportSpec(heightPx = 720),
        ExportSpec(bitrate = Bitrate.mbps(8)),
        ExportSpec(quality = VideoQuality.High),
      )
    val edit = edit(fixture.clip(hdr = HdrTransfer.Pq))

    for (spec in settings) {
      export(edit, spec, noHdrEncoder).hdr shouldBe ResolvedHdr.ToneMap
      preview(edit, spec, noHdrEncoder).hdr shouldBe ResolvedHdr.ToneMap
    }
    export(edit, ExportSpec(), noHdrEncoder).hdr shouldBe ResolvedHdr.Keep
  }

  @Test
  fun `a capped preview of an export that copies keeps the grade the export keeps`() {
    val edit = edit(fixture.clip(hdr = HdrTransfer.Pq))
    val capped = preview(edit, ExportSpec(), noHdrEncoder, frame = ExportSpec(heightPx = 720))

    export(edit, ExportSpec(), noHdrEncoder).hdr shouldBe ResolvedHdr.Keep
    capped.hdr shouldBe ResolvedHdr.Keep
    capped.hdrTransfer shouldBe HdrTransfer.Pq
    capped.output.size.height shouldBe 720
  }

  @Test
  fun `a capped preview keeps a copied grade on a device with no encoder for it`() {
    val h264Only = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264)), hdr = false)
    val edit = edit(fixture.clip(hdr = HdrTransfer.Pq))

    export(edit, ExportSpec(), h264Only).hdr shouldBe ResolvedHdr.Keep
    preview(edit, ExportSpec(), h264Only, frame = ExportSpec(heightPx = 720)).hdr shouldBe ResolvedHdr.Keep
  }

  @Test
  fun `each mode on an hdr source previews what its export resolves to`() {
    val edit = edit(fixture.clip(hdr = HdrTransfer.Hlg))
    val expected =
      mapOf(
        HdrMode.Auto to ResolvedHdr.Keep,
        HdrMode.KeepHdr to ResolvedHdr.Keep,
        HdrMode.ToneMapToSdr to ResolvedHdr.ToneMap,
      )

    for ((mode, hdr) in expected) {
      val spec = ExportSpec(hdr = mode)
      val exported = export(edit, spec, encodesHdr)
      val previewed = preview(edit, spec, encodesHdr)

      exported.hdr shouldBe hdr
      previewed.hdr shouldBe hdr
      previewed.hdrTransfer shouldBe exported.hdrTransfer
    }
  }

  @Test
  fun `every mode on an sdr source reads as keep`() {
    val edit = edit(fixture.clip())

    for (mode in HdrMode.entries) {
      grade(edit, ExportSpec(hdr = mode), noHdrEncoder) shouldBe ExportGrade.Settled(ResolvedHdr.Keep)
    }
  }

  @Test
  fun `an effect that needs sdr previews the tone map its export is forced to`() {
    val edit = edit(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(needsSdr)))

    export(edit, ExportSpec(), encodesHdr).hdr shouldBe ResolvedHdr.ToneMap
    preview(edit, ExportSpec(), encodesHdr).hdr shouldBe ResolvedHdr.ToneMap
  }

  @Test
  fun `a kept grade with no way to keep it is refused in the words of the export`() {
    val forced = edit(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(needsSdr)))
    val copyBlocked = edit(fixture.clip(hdr = HdrTransfer.Pq))
    val keep = ExportSpec(hdr = HdrMode.KeepHdr)
    val keepNamed = ExportSpec(hdr = HdrMode.KeepHdr, bitrate = Bitrate.mbps(8))

    fun exportRefusal(
      edit: EditComposition,
      spec: ExportSpec,
      device: DeviceCapabilities,
    ): FilmstripError =
      fixture
        .plan(edit, spec, device)
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()

    grade(forced, keep, encodesHdr) shouldBe ExportGrade.Refused(exportRefusal(forced, keep, encodesHdr))
    grade(copyBlocked, keepNamed, noHdrEncoder) shouldBe
      ExportGrade.Refused(exportRefusal(copyBlocked, keepNamed, noHdrEncoder))
  }

  @Test
  fun `a setting the export refuses is refused in the words of the export`() {
    val source = edit(fixture.clip(hdr = HdrTransfer.Pq))
    val noHeight = ExportSpec(heightPx = 0)
    val refusal =
      fixture
        .plan(source, noHeight, encodesHdr)
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()

    grade(source, noHeight, encodesHdr) shouldBe ExportGrade.Refused(refusal)
  }

  @Test
  fun `a strict spec the export refuses still previews the auto grade`() {
    val edit = edit(fixture.clip(hdr = HdrTransfer.Pq))
    val strict = ExportSpec(bitrate = Bitrate.mbps(8), strict = true)

    fixture.plan(edit, strict, noHdrEncoder).shouldBeInstanceOf<Verdict.Incapable>()
    grade(edit, strict, noHdrEncoder) shouldBe ExportGrade.Settled(ResolvedHdr.ToneMap)
  }
}
