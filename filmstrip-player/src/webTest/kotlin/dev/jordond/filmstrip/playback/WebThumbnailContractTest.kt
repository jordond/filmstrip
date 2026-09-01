package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.transform.internal.seekTolerance
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * What the strip's frames have to agree with, which is the file an export writes.
 *
 * A thumbnail is a third lowering of the same edit, beside the preview and the export, and it is
 * the one nobody looks at closely. These pin it to the export the way [WebPixelContractTest] pins
 * the preview.
 */
class WebThumbnailContractTest {
  @Test
  fun `a thumbnail matches the export at the frame it rendered`() =
    contractTest {
      val composition = webFixtureComposition(listOf(Brightness(DIM)))
      val request =
        ThumbnailRequest(composition, PROBE_POSITIONS.first(), FIXTURE_FRAME.height, REVISION, precise = true)

      val thumbnail = source(request).awaitThumbnail(request)
      try {
        thumbnail.frame().size shouldBe FIXTURE_FRAME

        val exported = webExportFrame(composition, thumbnail.presentationTime)
        assertFramesSimilar(
          expected = exported,
          actual = thumbnail.frame(),
          minSsim = ENCODED_MIN_SSIM,
          message = "the thumbnail and the export disagree at ${thumbnail.presentationTime}",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  /**
   * The compositor decodes up to the position it was given, so it answers the same either way and
   * this holds it to the tighter of the two bounds under both.
   *
   * The position sits well inside a group of pictures, so a source that had quietly started
   * snapping to the nearest sync sample would land several frames out and fail.
   */
  @Test
  fun `a thumbnail lands on the frame asked for however precise the request was`() =
    contractTest {
      val composition = webFixtureComposition()
      val exact = seekTolerance(precise = true, FIXTURE_FRAME_STEP, FIXTURE_SYNC_INTERVAL)

      for (precise in listOf(true, false)) {
        val request = ThumbnailRequest(composition, MID_GOP_POSITION, FIXTURE_FRAME.height, REVISION, precise)
        val thumbnail = source(request).awaitThumbnail(request)
        try {
          withClue("precise=$precise landed at ${thumbnail.presentationTime}") {
            thumbnail.driftFrom(MID_GOP_POSITION) shouldBeLessThanOrEqualTo exact
          }
        } finally {
          thumbnail.image.close()
        }
      }
    }

  @Test
  fun `a cancelled request never delivers`() =
    contractTest {
      val composition = webFixtureComposition(listOf(Brightness(DIM)))
      val request =
        ThumbnailRequest(composition, PROBE_POSITIONS.last(), FIXTURE_FRAME.height, REVISION, precise = true)
      var delivered: ThumbnailResult? = null

      source(request).requestThumbnail(request) { delivered = it }.cancel()
      settle()
      delivered shouldBe null

      // The same source and the same request, uncancelled, so the silence above is cancellation
      // rather than a source that cannot serve this edit at all.
      source(request).awaitThumbnail(request).image.close()
    }

  private fun source(request: ThumbnailRequest): ThumbnailSource =
    createThumbnailSource(request, CONTRACT_COMPONENTS) ?: fail("no thumbnail source claimed the fixture")

  private companion object {
    // Any value. The source does not read it, and these tests never ask twice under one revision.
    const val REVISION = 1L

    // An effect over the whole composition, so a source that quietly skipped the chain would hand
    // back the source frame and fail rather than pass.
    const val DIM = 0.4f

    // The threshold WebPixelContractTest measured for a rendered frame against one that has been
    // through the encoder.
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
 * How far the frame that came back sits from the time [asked] for, in either direction.
 */
private fun ThumbnailResult.Success.driftFrom(asked: Duration): Duration = (presentationTime - asked).absoluteValue

/**
 * This thumbnail's pixels, in the form the comparison helpers take.
 */
private fun ThumbnailResult.Success.frame(): TestFrame = image.toTestFrame()

private fun PlatformImage.toTestFrame(): TestFrame = TestFrame(toRgba8888(), Size(widthPx, heightPx))
