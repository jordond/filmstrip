package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.player.CAP_HEIGHT
import dev.jordond.filmstrip.player.FIXTURE_FRAME
import dev.jordond.filmstrip.player.FIXTURE_FRAME_STEP
import dev.jordond.filmstrip.player.PROBE_POSITIONS
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.appleExportLowering
import dev.jordond.filmstrip.player.appleFixtureComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.pumpMainRunLoopDuringContracts
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.time.Duration

class AvFrameReadbackMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `nothing loaded is refused straight away`() =
    contractTest { scope ->
      val answers = mutableListOf<ReadbackResult>()

      readback(scope, av = null).requestFrame(PROBE_POSITIONS.first()) { answers += it }

      val failure = answers.single().shouldBeInstanceOf<ReadbackResult.Failure>()
      failure.error.shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
    }

  @Test
  fun `a frame is read back at the time asked with the preview's own scale`() =
    contractTest { scope ->
      val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
      val position = PROBE_POSITIONS.first()

      val frame = read(readback(scope, av, scale = SCALE), position).frame

      (frame.presentationTime - position).absoluteValue shouldBeLessThan FIXTURE_FRAME_STEP / 2
      frame.size shouldBe FIXTURE_FRAME
      frame.pixels.size shouldBe FIXTURE_FRAME.width * FIXTURE_FRAME.height * CHANNELS
      frame.renderScale shouldBe SCALE
      frame.colorSpace shouldBe ColorSpace.Bt709
    }

  @Test
  fun `a capped policy reads back a frame no taller than the cap`() =
    contractTest { scope ->
      val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
      val policy = PreviewQualityPolicy.CapHeight(CAP_HEIGHT)

      val frame = read(readback(scope, av, policy = policy), PROBE_POSITIONS.last()).frame

      frame.size shouldBe Size(FIXTURE_FRAME.width * CAP_HEIGHT / FIXTURE_FRAME.height, CAP_HEIGHT)
    }

  @Test
  fun `a cap at or above the frame reads back the whole frame`() =
    contractTest { scope ->
      val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
      val policy = PreviewQualityPolicy.CapHeight(FIXTURE_FRAME.height)

      read(readback(scope, av, policy = policy), PROBE_POSITIONS.last()).frame.size shouldBe FIXTURE_FRAME
    }

  @Test
  fun `a cancelled request is never answered`() =
    contractTest { scope ->
      val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
      val answers = mutableListOf<ReadbackResult>()

      readback(scope, av).requestFrame(PROBE_POSITIONS.first()) { answers += it }.cancel()
      settle()

      answers.shouldBeEmpty()
    }

  private fun readback(
    scope: CoroutineScope,
    av: AvComposition?,
    policy: PreviewQualityPolicy = PreviewQualityPolicy.Full,
    scale: Float = 1f,
  ): AvFrameReadback = AvFrameReadback(scope, { av }, { policy }, { scale })

  private suspend fun read(
    readback: AvFrameReadback,
    position: Duration,
  ): ReadbackResult.Success {
    var answer: ReadbackResult? = null
    readback.requestFrame(position) { answer = it }
    awaitContract("a readback at $position") { answer != null }
    return answer.shouldBeInstanceOf<ReadbackResult.Success>()
  }

  private companion object {
    const val SCALE = 0.75f
    const val CHANNELS = 4
  }
}
