@file:OptIn(ExperimentalForeignApi::class)

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
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAssetImageGeneratorCompletionHandler
import platform.AVFoundation.AVAssetImageGeneratorResult
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreMedia.CMTime
import platform.Foundation.NSError
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

  @Test
  fun `a generator that never answers is asked again and the fresh one answers`() =
    contractTest { scope ->
      val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
      var attempts = 0
      val readback =
        readback(scope, av, deadline = SHORT_DEADLINE) { generator, time, handler ->
          if (attempts++ > 0) generateFrame(generator, time, handler)
        }

      val frame = read(readback, PROBE_POSITIONS.first()).frame

      attempts shouldBe 2
      frame.size shouldBe FIXTURE_FRAME
    }

  @Test
  fun `two generators that never answer fail the request naming the stall`() =
    contractTest { scope ->
      val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
      val answers = mutableListOf<ReadbackResult>()
      var attempts = 0
      val readback = readback(scope, av, deadline = SHORT_DEADLINE) { _, _, _ -> attempts++ }

      readback.requestFrame(PROBE_POSITIONS.first()) { answers += it }
      awaitContract("a stalled readback to fail") { answers.isNotEmpty() }
      settle()

      attempts shouldBe 2
      val failure = answers.single().shouldBeInstanceOf<ReadbackResult.Failure>()
      failure.error.shouldBeInstanceOf<PlaybackError.Underlying>().message shouldBe
        stalledReadbackMessage(PROBE_POSITIONS.first(), SHORT_DEADLINE)
    }

  @Test
  fun `a generator answering inside the deadline is delivered once with no retry`() =
    contractTest { scope ->
      val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
      val answer = generatedAnswer(scope, av)
      val answers = mutableListOf<ReadbackResult>()
      var attempts = 0
      val readback =
        readback(scope, av, deadline = SHORT_DEADLINE) { _, _, handler ->
          attempts++
          scope.launch {
            delay(SHORT_DEADLINE / 2)
            answer.handTo(handler)
          }
        }

      try {
        readback.requestFrame(PROBE_POSITIONS.first()) { answers += it }
        awaitContract("a late readback") { answers.isNotEmpty() }
        delay(SHORT_DEADLINE * 3)
      } finally {
        CGImageRelease(answer.image)
      }

      attempts shouldBe 1
      answers.single().shouldBeInstanceOf<ReadbackResult.Success>()
    }

  @Test
  fun `a cancelled request is not asked again`() =
    contractTest { scope ->
      val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
      val answers = mutableListOf<ReadbackResult>()
      var attempts = 0
      val readback = readback(scope, av, deadline = SHORT_DEADLINE) { _, _, _ -> attempts++ }

      readback.requestFrame(PROBE_POSITIONS.first()) { answers += it }.cancel()
      delay(SHORT_DEADLINE * 3)

      attempts shouldBe 1
      answers.shouldBeEmpty()
    }

  private fun readback(
    scope: CoroutineScope,
    av: AvComposition?,
    policy: PreviewQualityPolicy = PreviewQualityPolicy.Full,
    scale: Float = 1f,
    deadline: Duration = AV_READBACK_DEADLINE,
    generate: FrameGeneration = ::generateFrame,
  ): AvFrameReadback = AvFrameReadback(scope, { av }, { policy }, { scale }, deadline, generate)

  // One answer from the real generator, decoded before any deadline runs so a stand-in can hand it back on time.
  private suspend fun generatedAnswer(
    scope: CoroutineScope,
    av: AvComposition,
  ): GeneratedAnswer {
    var answer: GeneratedAnswer? = null
    val capturing =
      readback(scope, av) { generator, time, handler ->
        generateFrame(generator, time) { requested, image, actual, result, error ->
          answer = GeneratedAnswer(requested, CGImageRetain(image), actual, result, error)
          handler?.invoke(requested, image, actual, result, error)
        }
      }
    read(capturing, PROBE_POSITIONS.first())
    return checkNotNull(answer)
  }

  private class GeneratedAnswer(
    val requested: CValue<CMTime>,
    val image: CGImageRef?,
    val actual: CValue<CMTime>,
    val result: AVAssetImageGeneratorResult,
    val error: NSError?,
  ) {
    fun handTo(handler: AVAssetImageGeneratorCompletionHandler) {
      handler?.invoke(requested, image, actual, result, error)
    }
  }

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
    val SHORT_DEADLINE = 300.milliseconds
  }
}
