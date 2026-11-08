package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media3.internal.preview.Media3Preview
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.player.CONTRACT_COMPONENTS
import dev.jordond.filmstrip.player.FIXTURE_FRAME_STEP
import dev.jordond.filmstrip.player.PROBE_POSITIONS
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.androidFixtureComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.contractContext
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.fail

/**
 * The media3 readback taken apart from the engine, over a graph lowered the way the engine lowers it.
 */
class Media3FrameReadbackMediaTest {
  @Test
  fun nothingLoadedIsRefusedStraightAway() =
    contractTest { scope ->
      val readback = readback(scope, preview = null)
      try {
        val answers = mutableListOf<ReadbackResult>()

        readback.requestFrame(PROBE_POSITIONS.first()) { answers += it }

        val failure = answers.single().shouldBeInstanceOf<ReadbackResult.Failure>()
        failure.error.shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
      } finally {
        readback.dispose()
      }
    }

  @Test
  fun aFrameIsReadBackOnTheGridWithThePreviewsOwnScale() =
    contractTest { scope ->
      val readback = readback(scope, fixturePreview())
      try {
        val position = PROBE_POSITIONS.last()
        var answer: ReadbackResult? = null

        readback.requestFrame(position) { answer = it }
        awaitContract("a readback at $position") { answer != null }

        val frame = answer.shouldBeInstanceOf<ReadbackResult.Success>().frame
        (frame.presentationTime - position).absoluteValue shouldBeLessThanOrEqualTo FIXTURE_FRAME_STEP
        frame.pixels.size shouldBe frame.size.width * frame.size.height * CHANNELS
        frame.renderScale shouldBe SCALE
        frame.colorSpace shouldBe ColorSpace.Bt709
      } finally {
        readback.dispose()
      }
    }

  @Test
  fun aCancelledRequestIsNeverAnswered() =
    contractTest { scope ->
      val readback = readback(scope, fixturePreview())
      try {
        val answers = mutableListOf<ReadbackResult>()

        readback.requestFrame(PROBE_POSITIONS.first()) { answers += it }.cancel()
        settle()

        answers.shouldBeEmpty()
      } finally {
        readback.dispose()
      }
    }

  private fun readback(
    scope: CoroutineScope,
    preview: Media3Preview?,
  ): Media3FrameReadback = Media3FrameReadback(scope, contractContext(), { preview }, { SCALE }, { REVISION })

  private suspend fun fixturePreview(): Media3Preview {
    val planner = Media3PreviewPlanner(CONTRACT_COMPONENTS)
    val plan =
      when (val result = planner.plan(androidFixtureComposition(), PreviewQualityPolicy.Full, ExportSpec())) {
        is PlanResult.Ready -> result.plan
        is PlanResult.Refused -> fail("the preview refused the fixture: ${result.error.message}")
      }
    return plan.resolved.toMedia3Preview(plan.bounds)
  }

  private companion object {
    const val SCALE = 0.5f
    const val REVISION = 1L
    const val CHANNELS = 4
  }
}
