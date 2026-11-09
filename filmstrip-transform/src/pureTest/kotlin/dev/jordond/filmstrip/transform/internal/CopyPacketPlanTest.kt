package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.NONE_ON_GRID_COPY
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The window a copy's video packets are read over, and how the packets read there decide whether it stays a copy.
 */
class CopyPacketPlanTest {
  private val fixture = PlannerFixture()

  // The window opens on the sync sample the cut moved back to, not on the cut, so a read from the trim misses the
  // packets the copy opens with.
  @Test
  fun `a copy ending before its source reads the window it opens and closes on`() {
    val trimmed = fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), snapWithin = 1.seconds)
    val composition = composition(trimmed)

    val window = fixture.planner().copyPacketWindowOf(composition, ask(composition, opening = 500.milliseconds))

    window.shouldNotBeNull()
    window.source shouldBe trimmed.source
    window.from shouldBe 500.milliseconds
    window.to shouldBe 3_000.milliseconds
  }

  @Test
  fun `a copy running to its source's end reads no packets`() {
    val composition = composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 6_000.milliseconds)))

    fixture.planner().copyPacketWindowOf(composition, ask(composition, opening = 1_000.milliseconds)).shouldBeNull()
  }

  @Test
  fun `a transcode reads no packets`() {
    val composition = composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds)))

    fixture
      .planner()
      .copyPacketWindowOf(composition, ask(composition, ExportSpec.Upload, 1_000.milliseconds))
      .shouldBeNull()
  }

  @Test
  fun `packets with no presentation times move the copy to a transcode and say why`() {
    val composition = composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds)))
    val untimed = timed(from = 1_000.milliseconds, frames = 90).map { CopyPacket(Duration.INFINITE, it.key) }

    val verdict = fixture.planner().negotiate(composition, ask(composition, packets = untimed)).verdict

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.path shouldBe ExportPath.Transcode
    verdict.plan.copyBlockedBy shouldBe listOf(CopyBlocker.SourceUntimed)
  }

  @Test
  fun `timed packets keep the copy`() {
    val composition = composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds)))
    val packets = timed(from = 1_000.milliseconds, frames = 90)

    val verdict = fixture.planner().negotiate(composition, ask(composition, packets = packets)).verdict

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.path shouldBe ExportPath.Copy
    verdict.plan.copyBlockedBy.shouldBeEmpty()
  }

  // The count is settled once, on the plan, so no backend counts the packets again.
  @Test
  fun `a copy carries the count its packets give`() {
    val composition = composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds)))
    val packets = timed(from = 1_000.milliseconds, frames = 90)

    val resolved = fixture.planner().negotiate(composition, ask(composition, packets = packets)).composition

    resolved.shouldNotBeNull()
    val expected = resolved.copyPrefixOf(packets).shouldBeInstanceOf<CopyPrefix.Packets>()
    resolved.copyPrefix.shouldNotBeNull().count shouldBe expected.count
  }

  // Every packet is timed and all of them sit past the copy's end, so the count would be zero.
  @Test
  fun `a copy no packet lands on the grid of is refused by name`() {
    val trimmed = fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds))
    val composition = composition(trimmed)
    val pastTheGrid = timed(from = 3_000.milliseconds, frames = 30)

    val export = fixture.planner().negotiate(composition, ask(composition, packets = pastTheGrid))

    val verdict = export.verdict.shouldBeInstanceOf<Verdict.Incapable>()
    verdict.reasons.single() shouldBe FilmstripError.SourceUnreadable(trimmed.source.describe(), NONE_ON_GRID_COPY)
    export.composition.shouldBeNull()
  }

  @Test
  fun `a transcode carries no count`() {
    val composition = composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds)))
    val packets = timed(from = 1_000.milliseconds, frames = 90)

    val resolved = fixture.planner().negotiate(composition, ask(composition, ExportSpec.Upload, packets = packets))

    resolved.composition
      .shouldNotBeNull()
      .copyPrefix
      .shouldBeNull()
  }

  // The effect blocks the copy, so only the plan that leaves it out can copy, and that plan reads the same window.
  @Test
  fun `an edit with an effect this device cannot run names the window its fallback copies`() {
    val composition =
      composition(
        fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), effects = listOf(Rotate(90))),
      )

    val export = fixture.planner(resolvers = emptyList()).negotiate(composition, ask(composition))

    export.verdict
      .shouldBeInstanceOf<Verdict.Incapable>()
      .withoutUnsupported
      .shouldBeNull()
    val window = export.fallbackCopyWindow.shouldNotBeNull()
    window.from shouldBe 1_000.milliseconds
    window.to shouldBe 3_000.milliseconds
  }

  @Test
  fun `the fallback settles on the packets read over its window`() {
    val composition =
      composition(
        fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), effects = listOf(Rotate(90))),
      )
    val planner = fixture.planner(resolvers = emptyList())
    val export = planner.negotiate(composition, ask(composition))
    val packets = timed(from = 1_000.milliseconds, frames = 60)

    val settled = planner.negotiateFallback(composition, ask(composition, packets = packets), export)

    settled.fallbackCopyWindow.shouldBeNull()
    val fallback =
      settled.verdict
        .shouldBeInstanceOf<Verdict.Incapable>()
        .withoutUnsupported
        .shouldNotBeNull()
    fallback.plan.path shouldBe ExportPath.Copy
  }

  // A resolver is called once per pipeline, so naming the window asks none of them and negotiating asks each once.
  @Test
  fun `naming a copy's window lowers no effect`() {
    val fake = FakeResolver()
    var asked = 0
    val counting =
      EffectResolver { spec, capabilities, attributes ->
        asked++
        fake.resolve(spec, capabilities, attributes)
      }
    val composition =
      composition(
        fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), effects = listOf(Sepia(1f))),
      )
    val planner = fixture.planner(resolvers = listOf(counting))

    planner.copyPacketWindowOf(composition, ask(composition)).shouldBeNull()
    asked shouldBe 0
    planner.negotiate(composition, ask(composition)).fallbackCopyWindow.shouldBeNull()
    asked shouldBe 1
  }

  @Test
  fun `the fallback of an edit with an effect this device cannot run is moved off the copy by untimed packets`() {
    val composition =
      composition(
        fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), effects = listOf(Rotate(90))),
      )
    val untimed = timed(from = 1_000.milliseconds, frames = 90).map { CopyPacket(Duration.INFINITE, it.key) }

    val verdict =
      fixture.planner(resolvers = emptyList()).negotiate(composition, ask(composition, packets = untimed)).verdict

    val fallback = verdict.shouldBeInstanceOf<Verdict.Incapable>().withoutUnsupported.shouldNotBeNull()
    fallback.plan.path shouldBe ExportPath.Transcode
    fallback.plan.copyBlockedBy shouldBe listOf(CopyBlocker.SourceUntimed)
  }

  private fun ask(
    composition: Composition,
    spec: ExportSpec = ExportSpec(),
    opening: Duration = 1_000.milliseconds,
    packets: List<CopyPacket>? = null,
  ): Ask {
    val source: MediaSource =
      composition.tracks
        .single()
        .clips
        .single()
        .source
    return Ask(
      spec = spec,
      device = fixture.device(),
      infos = fixture.infos(composition),
      openings = mapOf(source to opening),
      copyPackets = packets,
    )
  }

  private fun timed(
    from: Duration,
    frames: Int,
  ): List<CopyPacket> = (0 until frames).map { CopyPacket(from + it.seconds / 30, key = it == 0) }
}
