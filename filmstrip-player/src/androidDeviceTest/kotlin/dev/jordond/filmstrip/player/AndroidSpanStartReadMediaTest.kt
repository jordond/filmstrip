package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A preview read as far into a clip's item as the clip sits into the timeline shows the frame from there.
 *
 * A frame reader answers with the first frame it decodes when that frame's time is the one it is asked for. The second
 * clip here opens [OPENING] into the timeline, so a read [OPENING] into its item asks for the time its first frame
 * would carry on the composition's clock.
 */
class AndroidSpanStartReadMediaTest {
  @Test
  fun aReadAsFarIntoTheItemAsTheClipStartsShowsThatFrame() =
    contractTest(BODY_BUDGET) { scope ->
      val moving = AndroidClipParity.movingSource()
      for (speed in listOf(1.0, SPED_UP)) {
        val second = Clip(moving, TimeRange.of(Duration.ZERO, 1.seconds), speed = speed)
        val edit = Composition(listOf(Track(listOf(Clip(moving, TimeRange.of(Duration.ZERO, OPENING)), second))))
        val slot = outputSlotOf(OPENING + outputLengthOf(OPENING, speed), FIXTURE_FRAME_RATE)
        val at = outputSlotTime(slot, FIXTURE_FRAME_RATE)

        val shown = previewFrame(AndroidClipParity.createEngine(scope), edit, at)
        withClue("at $speed the preview at slot $slot landed at ${shown.presentationTime}") {
          outputSlotOf(shown.presentationTime, FIXTURE_FRAME_RATE) shouldBe slot
        }
        assertFramesSimilar(
          expected = AndroidClipParity.comparedPart(AndroidClipParity.exportFrame(edit, at)),
          actual = AndroidClipParity.comparedPart(shown.asTestFrame()),
          minPsnrDb = AndroidClipParity.minPsnrDb,
          minSsim = AndroidClipParity.minSsimAt(slot),
          message = "at $speed the preview at slot $slot is not the frame the file shows there",
        )
      }
    }

  private suspend fun previewFrame(
    engine: PlayerEngine,
    edit: Composition,
    at: Duration,
  ): ReadbackFrame {
    var frame: ReadbackFrame? = null
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit to be presentable") { recorder.lastState.hasComposition }
      frame = engine.readback.awaitFrame(at)
    }
    return checkNotNull(frame)
  }

  private companion object {
    const val SPED_UP = 1.5
    val OPENING = 500.milliseconds
    val BODY_BUDGET = 3.minutes
  }
}
