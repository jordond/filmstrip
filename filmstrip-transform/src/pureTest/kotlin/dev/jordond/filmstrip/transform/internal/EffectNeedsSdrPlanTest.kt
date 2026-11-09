package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * What the planner does with an hdr source when an effect in the edit needs sdr frames.
 *
 * Covers the tone map an auto export reports and which effect it names, the same effect at each scope, a strict spec, a
 * kept grade, and a vhs at and above zero intensity.
 */
class EffectNeedsSdrPlanTest {
  private val fixture = PlannerFixture()
  private val encodesHdr = fixture.device(hdr = true)
  private val needsSdr =
    object : EffectSpec {
      override val id = "test.needsSdr"
      override val requiresSdr = true
    }

  @Test
  fun `an effect that needs sdr tone-maps auto on a device that encodes hdr and names the effect`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(needsSdr))),
        device = encodesHdr,
      )

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    val adjustment = degraded.adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.HdrToneMapped
    adjustment.message shouldContain needsSdr.id
    adjustment.message shouldNotContain "device"
    fixture
      .resolve(
        composition(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(needsSdr))),
        device = encodesHdr,
      ).hdr shouldBe
      ResolvedHdr.ToneMap
  }

  @Test
  fun `an effect that needs sdr at track and composition scope counts the same`() {
    val atTrack =
      Composition(listOf(Track(listOf(fixture.clip(hdr = HdrTransfer.Pq)), effects = listOf(needsSdr))))
    val atComposition =
      Composition(listOf(Track(listOf(fixture.clip(hdr = HdrTransfer.Pq)))), effects = listOf(needsSdr))

    fixture.resolve(atTrack, device = encodesHdr).hdr shouldBe ResolvedHdr.ToneMap
    fixture.resolve(atComposition, device = encodesHdr).hdr shouldBe ResolvedHdr.ToneMap
  }

  @Test
  fun `tone map to sdr with an effect that needs it adds no concession`() {
    val edit = composition(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(needsSdr)))

    fixture
      .plan(
        edit,
        ExportSpec(hdr = HdrMode.ToneMapToSdr),
        device = encodesHdr,
      ).shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun `keep hdr with an effect that needs sdr is refused by the effect's name`() {
    val edit = composition(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(needsSdr)))

    val reason =
      fixture
        .plan(edit, ExportSpec(hdr = HdrMode.KeepHdr), device = encodesHdr)
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()
        .toString()

    reason shouldContain needsSdr.id
    reason shouldNotContain "encode"
  }

  @Test
  fun `a strict spec refuses the tone map an effect that needs sdr forces`() {
    val edit = composition(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(needsSdr)))

    fixture
      .plan(edit, ExportSpec(strict = true), device = encodesHdr)
      .shouldBeInstanceOf<Verdict.Incapable>()
      .reasons
      .joinToString() shouldContain needsSdr.id
  }

  @Test
  fun `an sdr source is not touched by an effect that needs sdr`() {
    val verdict = fixture.plan(composition(fixture.clip(effects = listOf(needsSdr))), device = encodesHdr)

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a vhs at zero intensity forces nothing`() {
    val edit = composition(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(Vhs(intensity = 0f))))

    fixture.resolve(edit, device = encodesHdr).hdr shouldBe ResolvedHdr.Keep
  }

  @Test
  fun `a vhs at a half forces the tone map`() {
    val edit = composition(fixture.clip(hdr = HdrTransfer.Pq, effects = listOf(Vhs(intensity = 0.5f))))

    fixture.resolve(edit, device = encodesHdr).hdr shouldBe ResolvedHdr.ToneMap
  }
}
