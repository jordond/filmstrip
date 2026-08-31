package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail

/**
 * What the strip's frames have to agree with, which is the file an export writes.
 *
 * A thumbnail is a third lowering of the same edit, beside the preview and the export, and it is
 * the one nobody looks at closely. These pin it to the export the way [JvmPixelContractTest] pins
 * the preview, and hold it to the same process hygiene the transport is held to.
 */
class JvmThumbnailContractTest {
  @Test
  fun `a thumbnail matches the export at the frame it rendered`() =
    contractTest {
      val composition = jvmFixtureComposition(listOf(Brightness(DIM)))
      val request = ThumbnailRequest(composition, PROBE_POSITIONS.first(), FIXTURE_FRAME.height, REVISION)

      val thumbnail = source(request).awaitThumbnail(request)
      try {
        thumbnail.frame().size shouldBe FIXTURE_FRAME

        val exported = jvmExportFrame(composition, thumbnail.presentationTime)
        assertFramesSimilar(
          expected = exported,
          actual = thumbnail.frame(),
          minPsnrDb = ENCODED_MIN_PSNR_DB,
          minSsim = ENCODED_MIN_SSIM,
          message = "the thumbnail and the export disagree at ${thumbnail.presentationTime}",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun `a served thumbnail leaves no pump running`() =
    contractTest {
      val baseline = runningPumps()
      val composition = jvmFixtureComposition(listOf(Brightness(DIM)))
      val request = ThumbnailRequest(composition, PROBE_POSITIONS.last(), FIXTURE_FRAME.height, REVISION)

      source(request).awaitThumbnail(request).image.close()

      settle()
      (runningPumps() - baseline).shouldBeEmpty()
    }

  // The wait is on the processes reading the fixture, not on whatever ffmpeg is alive. Lowering the
  // edit measures the encoder ladder first, which spawns a handful of its own against a synthetic
  // input, and a suite that cancelled as soon as any ffmpeg appeared would be cancelling before the
  // pump it means to kill had started.
  @Test
  fun `a cancelled request kills the pump it spawned`() =
    contractTest {
      val baseline = runningFramePumps()
      val composition = jvmFixtureComposition(listOf(Brightness(DIM)))
      val request = ThumbnailRequest(composition, PROBE_POSITIONS.last(), FIXTURE_FRAME.height, REVISION)
      val delivered = AtomicReference<ThumbnailResult?>(null)

      val handle = source(request).requestThumbnail(request) { delivered.set(it) }
      awaitContract("the thumbnail pump to spawn") { (runningFramePumps() - baseline).isNotEmpty() }
      handle.cancel()

      // Stopping the child is a closed pipe, then a signal, then a grace period, so the pump going
      // is waited for rather than read at one instant after the cancel.
      awaitContract("the cancelled pump to go") { (runningFramePumps() - baseline).none { it.isAlive } }

      settle()
      delivered.get() shouldBe null
    }

  private fun source(request: ThumbnailRequest): ThumbnailSource =
    createThumbnailSource(request, CONTRACT_COMPONENTS) ?: fail("no thumbnail source claimed the fixture")

  private companion object {
    // Any value. The source does not read it, and these tests never ask twice under one revision.
    const val REVISION = 1L

    // An effect over the whole composition, so a source that quietly skipped the chain would hand
    // back the source frame and fail rather than pass.
    const val DIM = 0.4f

    // The thresholds JvmPixelContractTest measured for a rendered frame against a frame that has
    // been through the encoder.
    const val ENCODED_MIN_PSNR_DB = 42.0
    const val ENCODED_MIN_SSIM = 0.985
  }
}

/**
 * Asks for one thumbnail and suspends until it arrives, failing the test when it cannot be made.
 */
private suspend fun ThumbnailSource.awaitThumbnail(request: ThumbnailRequest): ThumbnailResult.Success =
  suspendCancellableCoroutine { continuation ->
    val handle: Cancellable =
      requestThumbnail(request) { result ->
        if (!continuation.isActive) return@requestThumbnail
        when (result) {
          is ThumbnailResult.Success -> continuation.resume(result)
          is ThumbnailResult.Failure -> continuation.resume(fail("the thumbnail failed: ${result.error.message}"))
        }
      }
    continuation.invokeOnCancellation { handle.cancel() }
  }

/**
 * This thumbnail's pixels, in the form the comparison helpers take.
 */
private fun ThumbnailResult.Success.frame(): TestFrame = image.toTestFrame()

private fun PlatformImage.toTestFrame(): TestFrame = TestFrame(toRgba8888(), Size(widthPx, heightPx))
