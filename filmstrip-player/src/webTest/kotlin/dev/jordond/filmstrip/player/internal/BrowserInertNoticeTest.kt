package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.player.CONTRACT_COMPONENTS
import dev.jordond.filmstrip.player.CONTRACT_FILMSTRIP
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.webFixtureComposition
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * The browser preview says what it left out of an edit in the same words the export plan for that edit does.
 */
class BrowserInertNoticeTest {
  @Test
  fun `a preview carries the notices the export plan carries`() =
    contractTest {
      val composition = webFixtureComposition(listOf(Brightness(1f)))

      val preview =
        BrowserPreviewPlanner(CONTRACT_COMPONENTS)
          .plan(composition, PreviewQualityPolicy.Full)
          .shouldBeInstanceOf<PlanResult.Ready<BrowserPreviewPlan>>()
          .plan
      val export =
        CONTRACT_FILMSTRIP
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan

      export.inertNotices.shouldNotBeEmpty()
      preview.info.inertNotices shouldBe export.inertNotices
    }
}
