package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.player.CONTRACT_COMPONENTS
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.webFixtureComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * What the browser preview says about how the exported grade looks, which follows the grade the plan settled on.
 */
class BrowserFidelityTest {
  @Test
  fun `a kept grade is not what the canvas shows`() {
    hdrAppearance(ResolvedHdr.Keep) shouldBe PreviewFidelity.NotPreviewable
  }

  @Test
  fun `a tone mapped export is what the canvas shows`() {
    hdrAppearance(ResolvedHdr.ToneMap) shouldBe PreviewFidelity.Exact
  }

  // The fixture is SDR, so every mode settles on the kept grade and the note follows that, not the mode asked for.
  @Test
  fun `a planned preview under a mirrored mode carries the note for the grade it settled on`() =
    runTest {
      HdrMode.entries.forEach { mode ->
        val plan =
          BrowserPreviewPlanner(CONTRACT_COMPONENTS)
            .plan(webFixtureComposition(), PreviewQualityPolicy.Full, ExportSpec(hdr = mode))
            .shouldBeInstanceOf<PlanResult.Ready<BrowserPreviewPlan>>()
            .plan

        plan.resolved.hdr shouldBe ResolvedHdr.Keep
        plan.info.fidelity
          .single { it.property == OutputProperty.HdrAppearance }
          .fidelity shouldBe
          hdrAppearance(ResolvedHdr.Keep)
      }
    }

  private fun hdrAppearance(hdr: ResolvedHdr): PreviewFidelity =
    browserFidelity(hdr).single { it.property == OutputProperty.HdrAppearance }.fidelity
}
