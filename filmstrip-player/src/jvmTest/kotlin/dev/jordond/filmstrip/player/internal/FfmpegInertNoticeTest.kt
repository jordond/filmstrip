package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ffmpegExportEngine
import dev.jordond.filmstrip.player.CONTRACT_COMPONENTS
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.jvmFixtureComposition
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * The ffmpeg preview says what it left out of an edit in the same words the export plan for that edit does.
 */
class FfmpegInertNoticeTest {
  @Test
  fun `a preview carries the notices the export plan carries`() =
    runBlocking<Unit> {
      val composition = jvmFixtureComposition(listOf(Brightness(1f)))

      val preview =
        FfmpegPreviewPlanner(CONTRACT_COMPONENTS)
          .plan(composition, PreviewQualityPolicy.Full, ExportSpec())
          .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
          .plan
      val export =
        ffmpegExportEngine(CONTRACT_COMPONENTS)
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan

      export.inertNotices.shouldNotBeEmpty()
      preview.info.inertNotices shouldBe export.inertNotices
    }
}
