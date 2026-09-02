package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settleForAbsence
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
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * What the strip's frames have to agree with, which is the file an export writes.
 *
 * A thumbnail is a third lowering of the same edit, beside the preview and the export, and it is
 * the one nobody looks at closely. These pin it to the export the same way [ApplePixelContractTest]
 * pins the preview.
 */
class AppleThumbnailContractTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a thumbnail matches the export at the frame it rendered`() =
    contractTest {
      val composition = appleFixtureComposition()
      val request =
        ThumbnailRequest(composition, PROBE_POSITIONS.first(), FIXTURE_FRAME.height, REVISION, precise = true)

      val thumbnail = source(request).awaitThumbnail(request)
      try {
        thumbnail.frame().size shouldBe FIXTURE_FRAME

        val exported = appleExportFrame(composition, thumbnail.presentationTime)
        assertFramesSimilar(
          expected = exported,
          actual = thumbnail.frame(),
          message = "the thumbnail and the export disagree at ${thumbnail.presentationTime}",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  /**
   * The caption is laid out against the frame the export writes and only the raster comes down, so
   * a strip frame breaks its lines on the same words. Laid out against the strip's own width the
   * caption takes a different number of lines, which moves the plate and drops both metrics well
   * below these thresholds.
   */
  @Test
  fun `a thumbnail under a caption breaks its lines where the export does`() =
    contractTest {
      val composition = appleFixtureComposition(listOf(TextOverlay(CAPTION, CAPTION_STYLE)))
      val request = ThumbnailRequest(composition, PROBE_POSITIONS.first(), CAP_HEIGHT, REVISION, precise = true)

      val thumbnail = source(request).awaitThumbnail(request)
      try {
        val frame = thumbnail.frame()
        frame.size.height shouldBe CAP_HEIGHT

        val exported = appleExportFrame(composition, thumbnail.presentationTime)
        exported.size shouldBe FIXTURE_FRAME

        assertFramesSimilar(
          expected = exported.scaledTo(frame.size),
          actual = frame,
          minPsnrDb = CAPTION_MIN_PSNR_DB,
          minSsim = CAPTION_MIN_SSIM,
          message = "the thumbnail wrapped the caption differently to the export",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  /**
   * A precise request pins the generator's tolerances to zero, and the video composition every
   * lowering here attaches renders on the output's frame grid, so the frame is the exact one under
   * both settings and this holds the source to the tighter of the two bounds either way.
   *
   * The position sits well inside a group of pictures. A generator handed the bare asset instead,
   * or left to its default tolerances with nothing forcing the grid, answers from the sync sample
   * eight frames further on and fails this.
   */
  @Test
  fun `a thumbnail lands on the frame asked for however precise the request was`() =
    contractTest {
      val composition = appleFixtureComposition()
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

  /**
   * A strip frame drawn from a photo rather than from a decoded clip.
   *
   * A still holds no track of its own and takes its slot from a generated segment, so the claim
   * that thumbnails come free once the export draws one is worth measuring rather than inheriting.
   * The frame is asked for inside the photo's span and not at either edge of it, then compared
   * against the file an export of the same edit writes at the time that came back.
   */
  @Test
  fun `a thumbnail inside a photo's span matches the export`() =
    contractTest {
      val composition = applePhotoComposition()
      val inside = CLIP_LENGTH + PHOTO_LENGTH * PHOTO_FRACTION
      val request = ThumbnailRequest(composition, inside, FIXTURE_FRAME.height, REVISION, precise = true)

      val thumbnail = source(request).awaitThumbnail(request)
      try {
        val frame = thumbnail.frame()
        frame.size shouldBe FIXTURE_FRAME

        // The photo itself, not only agreement with the export. Both lowerings drawing the seed's
        // own black frame would agree with each other and be wrong together.
        frame.centre() shouldBeCloseTo PHOTO_COLOR

        assertFramesSimilar(
          expected = appleExportFrame(composition, thumbnail.presentationTime),
          actual = frame,
          message = "the strip and the export disagree inside the photo at ${thumbnail.presentationTime}",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun `a cancelled request never delivers`() =
    contractTest {
      val composition = appleFixtureComposition()
      val request = ThumbnailRequest(composition, PROBE_POSITIONS.first(), CAP_HEIGHT, REVISION, precise = true)
      val delivered = AtomicReference<ThumbnailResult?>(null)

      source(request).requestThumbnail(request) { delivered.value = it }.cancel()

      // Long enough that the frame would have arrived several times over had cancelling not stopped
      // it, rather than long enough to have been missed once.
      settleForAbsence()
      delivered.value shouldBe null

      // The same source and the same request, uncancelled, so the silence above is cancellation
      // rather than a source that cannot serve this edit at all.
      val served = source(request).awaitThumbnail(request)
      served.image.close()
    }

  private fun source(request: ThumbnailRequest): ThumbnailSource =
    createThumbnailSource(request, CONTRACT_COMPONENTS) ?: fail("no thumbnail source claimed the fixture")

  private companion object {
    // Any value. The source does not read it, and these tests never ask twice under one revision.
    const val REVISION = 1L

    // The thresholds ApplePixelContractTest measured for a capped render of this caption against a
    // resampled export.
    const val CAPTION_MIN_PSNR_DB = 25.0
    const val CAPTION_MIN_SSIM = 0.96
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
