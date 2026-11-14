package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class PreviewClockLoweringTest {
  private val fixture = PlannerFixture()
  private val clock = PreviewClock { 1370.milliseconds }

  @Test
  fun `a preview clock reaches an effect at clip scope and at composition scope`() {
    val seen = Seen()

    val resolved = negotiate(seen, clock)

    seen.clocks shouldContainExactly listOf(CLIP to clock, MIX to clock)
    resolved.previewClock shouldBe clock
  }

  @Test
  fun `an export hands no effect a preview clock`() {
    val seen = Seen()

    val resolved = negotiate(seen, null)

    seen.clocks shouldContainExactly listOf(CLIP to null, MIX to null)
    resolved.previewClock shouldBe null
  }

  private fun negotiate(
    seen: Seen,
    previewClock: PreviewClock?,
  ): ResolvedComposition {
    val composition =
      Composition(
        listOf(Track(listOf(fixture.clip(effects = listOf(Ticking(CLIP)))))),
        effects = listOf(Ticking(MIX)),
      )
    return fixture
      .planner(resolvers = listOf(seen, FakeResolver()))
      .negotiate(
        composition,
        Ask(ExportSpec(), fixture.device(), fixture.infos(composition), previewClock = previewClock),
      ).composition
      .shouldNotBeNull()
  }

  private class Seen : EffectResolver {
    val clocks = mutableListOf<Pair<EffectId, PreviewClock?>>()

    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? {
      if (spec is Ticking) clocks += spec.id to attributes.previewClock
      return null
    }
  }

  private data class Ticking(
    override val id: EffectId,
  ) : EffectSpec {
    override val stage: EffectStage get() = EffectStage.Spatial
  }

  private companion object {
    val CLIP = EffectId("test.ticking.clip")
    val MIX = EffectId("test.ticking.mix")
  }
}
