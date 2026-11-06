package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.internal.bounds.readClips
import dev.jordond.filmstrip.media3.internal.preview.Media3Span
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The plan the media3 preview is lowered from, read against what an export of the same edit lays.
 *
 * Both read the samples each clip decodes off its container, and the items media3 plays are laid by them, so these run
 * on a device where the container can really be opened.
 */
class AndroidPreviewPlanTest {
  /**
   * A clip that opens inside a source frame is laid the way an export lays it.
   *
   * media3 opens such a clip on the next source frame, so it decodes short of the slots it covers and its last frame is
   * held to fill them. The preview's items and the spans a frame reader reads carry the same clipping and the same held
   * frame as the export's.
   */
  @Test
  fun aClipOpeningInsideASourceFrameIsLaidTheWayTheExportLaysIt() =
    runTest(timeout = TIMEOUT) {
      withContext(Dispatchers.Default) {
        val planned =
          Media3PreviewPlanner(
            CONTRACT_COMPONENTS,
          ).plan(openingInsideAFrame(), PreviewQualityPolicy.Full, ExportSpec())
        val plan = planned.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan

        val preview = plan.resolved.toMedia3Preview(plan.bounds)
        val exported = plan.resolved.toMedia3Preview(plan.resolved.readClips())

        // A clip that holds nothing is laid the same whether or not its container was read.
        withClue("the export holds no frame, so the case proves nothing") {
          exported.spans
            .first()
            .heldFrom
            .shouldNotBeNull()
        }
        preview.spans.map { it.laid() } shouldBe exported.spans.map { it.laid() }
      }
    }

  private fun Media3Span.laid(): Triple<Long, Long, Duration?> =
    Triple(item.clippingConfiguration.startPositionUs, item.clippingConfiguration.endPositionUs, heldFrom)

  private fun openingInsideAFrame(): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(
            listOf(
              Clip(androidFixtureClip(), TimeRange.of(HALF_A_FRAME, HALF_A_FRAME + 1.seconds)),
              Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
            ),
          ),
        ),
    )

  private companion object {
    val HALF_A_FRAME: Duration = FIXTURE_FRAME_STEP / 2
    val TIMEOUT: Duration = 1.minutes
  }
}
