package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.player.FIXTURE_FRAME_STEP
import dev.jordond.filmstrip.player.PROBE_POSITIONS
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.webFixtureComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class BrowserFrameReadbackTest {
  @Test
  fun `nothing loaded is refused straight away`() =
    contractTest { scope ->
      val answers = mutableListOf<ReadbackResult>()

      BrowserFrameReadback(scope, { null }, { 1f }).requestFrame(PROBE_POSITIONS.first()) { answers += it }

      val failure = answers.single().shouldBeInstanceOf<ReadbackResult.Failure>()
      failure.error.shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
    }

  @Test
  fun `a frame is read out of the compositor at the time asked with the preview's own scale`() =
    contractTest { scope ->
      val (plan, preview) = browserPreviewOf(webFixtureComposition())
      try {
        val position = PROBE_POSITIONS.last()
        var answer: ReadbackResult? = null

        BrowserFrameReadback(scope, { preview }, { SCALE }).requestFrame(position) { answer = it }
        awaitContract("a readback at $position") { answer != null }

        val frame = answer.shouldBeInstanceOf<ReadbackResult.Success>().frame
        (frame.presentationTime - position).absoluteValue shouldBeLessThan FIXTURE_FRAME_STEP / 2
        frame.size shouldBe plan.resolved.output.size
        frame.pixels.size shouldBe frame.size.width * frame.size.height * CHANNELS
        frame.renderScale shouldBe SCALE
        frame.colorSpace shouldBe ColorSpace.Bt709
      } finally {
        preview.release()
      }
    }

  @Test
  fun `a cancelled request is never answered`() =
    contractTest { scope ->
      val (_, preview) = browserPreviewOf(webFixtureComposition())
      try {
        val answers = mutableListOf<ReadbackResult>()
        val readback = BrowserFrameReadback(scope, { preview }, { 1f })

        readback.requestFrame(PROBE_POSITIONS.first()) { answers += it }.cancel()
        settle()

        answers.shouldBeEmpty()
      } finally {
        preview.release()
      }
    }

  private companion object {
    const val SCALE = 0.5f
    const val CHANNELS = 4
  }
}
