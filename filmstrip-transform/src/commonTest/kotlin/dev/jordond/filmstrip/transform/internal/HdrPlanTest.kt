package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * How the planner keeps, copies or tone-maps an hdr grade.
 *
 * Covers what an auto or strict export reports when the grade is tone-mapped, the codec an hdr encode falls back to,
 * sdr sources, and clips whose transfers disagree.
 */
class HdrPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `hdr is tone-mapped when the device can neither encode it nor copy it across`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg)), muxerAccepts = { false })

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    val adjustment = degraded.adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.HdrToneMapped
    adjustment.message shouldBe "This device cannot encode HDR, so the grade is tone-mapped to SDR."
  }

  // The same drop, reached the way a caller actually reaches it: the muxer would have copied this
  // source, and the edit written over it is what asked for an encoder no HDR fits through.
  @Test
  fun `an auto export tone-maps and reports it when the edit is what forced the encode`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg, effects = listOf(Brightness(0.5f)))))

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    degraded.plan.path shouldBe ExportPath.Transcode
    val adjustment = degraded.adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.HdrToneMapped
    adjustment.requested shouldBe HdrMode.Auto.name
    adjustment.resolved shouldBe HdrMode.ToneMapToSdr.name
    adjustment.message shouldBe "This device cannot encode HDR, so the grade is tone-mapped to SDR."
  }

  // The adjustment and the graph are read from the same negotiation, so a report that said one
  // thing while the composition did another would show up here.
  @Test
  fun `the tone map an auto export reports is the one the composition resolves to`() {
    val resolved = fixture.resolve(composition(fixture.clip(hdr = HdrTransfer.Hlg, effects = listOf(Brightness(0.5f)))))

    resolved.hdr shouldBe ResolvedHdr.ToneMap
    resolved.hdrTransfer shouldBe null
  }

  // The middle of the range the device answer covers: no HDR encoder is not itself a drop, since a
  // copy carries the grade without one, and reporting a tone map that never happened is as wrong
  // as staying quiet about one that did.
  @Test
  fun `an auto export keeps the grade through a copy on a device with no hdr encoder`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Pq)))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
    plan.copyBlockedBy.shouldBeEmpty()
  }

  // The other end: a device that encodes the grade keeps it through the encoder the edit forced,
  // so there is still nothing to report.
  @Test
  fun `an auto export that encodes the grade itself reports nothing`() {
    val composition = composition(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(Brightness(0.5f))))
    val verdict = fixture.plan(composition, device = fixture.device(hdr = true))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    plan.output.videoCodec shouldBe VideoCodec.Hevc
    fixture.resolve(composition, device = fixture.device(hdr = true)).hdrTransfer shouldBe HdrTransfer.Pq
  }

  // A mix is a reason on its own, so it stays the reason on a device that could not have encoded
  // the grade either. Blaming the encoder would have whoever reads it fix the wrong thing: another
  // device would tone-map this mix too.
  @Test
  fun `a mixed grade on a device with no hdr encoder still blames the mix`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Pq), fixture.clip()))

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    val adjustment = degraded.adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.HdrToneMapped
    adjustment.message shouldBe
      "These sources do not share one HDR transfer, so no single grade describes the output and " +
      "they are tone-mapped to SDR together."
  }

  // Asking for the tone map is what got it here too. The device could not have kept the grade
  // either, but a caller who named the outcome learns nothing from being told they reached it.
  @Test
  fun `an asked-for tone map reports nothing on a device that could not have kept the grade`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Hlg, effects = listOf(Brightness(0.5f)))),
        ExportSpec(hdr = HdrMode.ToneMapToSdr),
      )

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  // strict is a caller saying they wanted no surprises, and a dropped grade is the surprise the
  // whole adjustment exists for.
  @Test
  fun `a strict auto export refuses the tone map it did not ask for`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Hlg, effects = listOf(Brightness(0.5f)))),
        ExportSpec(strict = true),
      )

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "This device cannot encode HDR, so the grade is tone-mapped to SDR."
  }

  @Test
  fun `an asked-for tone map is not an adjustment`() {
    // Auto is the mode that can land somewhere else, so it is the one that reports. Asking for a
    // tone map and getting one is the request honoured, and calling that degraded costs a caller
    // matching on Capable the plan it can run, and strict the export entirely.
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Hlg)),
        ExportSpec(hdr = HdrMode.ToneMapToSdr),
        device = fixture.device(hdr = true),
      )

    verdict.shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun `a strict export survives the tone map it asked for`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Hlg)),
        ExportSpec(hdr = HdrMode.ToneMapToSdr, strict = true),
        device = fixture.device(hdr = true),
      )

    verdict.shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun `hdr is kept when the device can encode it on the only codec that can carry it`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg)), device = fixture.device(hdr = true))

    // Not H264, which is what the ladder prefers for everything else. Naming it here would have
    // the platform swap in HEVC anyway and the plan describe a file nobody wrote.
    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  @Test
  fun `asking for h264 on an hdr export reports the codec it had to use instead`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Hlg)),
        ExportSpec(videoCodec = VideoCodec.H264, hdr = HdrMode.KeepHdr),
        device = fixture.device(hdr = true),
      )

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    degraded.plan.output.videoCodec shouldBe VideoCodec.Hevc
    degraded.adjustments.single().kind shouldBe AdjustmentKind.CodecFallback
  }

  @Test
  fun `keeping hdr on a device that can neither encode it nor copy it across is refused`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Hlg)),
        ExportSpec(hdr = HdrMode.KeepHdr),
        muxerAccepts = { false },
      )

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  // An engine that can neither encode the grade, copy it across, nor bring it down has nowhere to
  // put an HDR source, and writing the untouched grade into an SDR file is the one answer that is
  // always wrong.
  @Test
  fun `an engine that can do none of the three refuses a graded source rather than flattening it`() {
    val composition = composition(fixture.clip(hdr = HdrTransfer.Pq))
    val verdict =
      fixture
        .planner(canToneMap = false, muxerAccepts = { false })
        .negotiate(composition, Ask(ExportSpec(), fixture.device(), fixture.infos(composition)))
        .verdict

    val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "This source carries an HDR grade and this engine can neither encode it nor tone-map it, so " +
      "there is nowhere to put it."
  }

  @Test
  fun `an sdr source is never tone-mapped whatever was asked for`() {
    // There is no grade to map, and saying otherwise costs the stream copy: a platform told to
    // tone-map has to decode every frame to do it.
    val resolved = fixture.resolve(composition(fixture.clip()), ExportSpec(hdr = HdrMode.ToneMapToSdr))

    resolved.hdr shouldBe ResolvedHdr.Keep
  }

  @Test
  fun `an sdr source keeps the copy it was planned for`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(hdr = HdrMode.ToneMapToSdr))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Copy
  }

  // Two clips that disagree about the grade have nothing one transfer describes, so keeping either
  // would tag the other's frames with a grade they do not have.
  @Test
  fun `sources that disagree about the transfer refuse to keep either grade`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Hlg), fixture.clip(hdr = HdrTransfer.Pq)),
        ExportSpec(hdr = HdrMode.KeepHdr),
        device = fixture.device(hdr = true),
      )

    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "These sources do not share one HDR transfer, so no single grade describes the output. Use " +
      "HdrMode.Auto or HdrMode.ToneMapToSdr to bring them down to SDR together."
  }

  // An HDR clip sat beside an SDR one is the same disagreement, and the one people actually build.
  @Test
  fun `an hdr clip beside an sdr clip refuses to keep the grade`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Pq), fixture.clip()),
        ExportSpec(hdr = HdrMode.KeepHdr),
        device = fixture.device(hdr = true),
      )

    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "These sources do not share one HDR transfer, so no single grade describes the output. Use " +
      "HdrMode.Auto or HdrMode.ToneMapToSdr to bring them down to SDR together."
  }

  @Test
  fun `mixed grades tone-map together rather than mislabelling the output`() {
    val verdict =
      fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Pq), fixture.clip()), device = fixture.device(hdr = true))

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
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
    val resolved =
      fixture.resolve(
        composition(fixture.clip(hdr = HdrTransfer.Pq), fixture.clip()),
        device = fixture.device(hdr = true),
      )

    resolved.hdr shouldBe ResolvedHdr.ToneMap
    resolved.hdrTransfer shouldBe null
  }

  // The middle of the range the mix rule covers: more than one clip is not itself a disagreement,
  // so clips that share a transfer still keep it.
  @Test
  fun `two clips that share a transfer keep it`() {
    val resolved =
      fixture.resolve(
        composition(fixture.clip(hdr = HdrTransfer.Hlg), fixture.clip(hdr = HdrTransfer.Hlg)),
        device = fixture.device(hdr = true),
      )

    resolved.hdr shouldBe ResolvedHdr.Keep
    resolved.hdrTransfer shouldBe HdrTransfer.Hlg
  }
}
