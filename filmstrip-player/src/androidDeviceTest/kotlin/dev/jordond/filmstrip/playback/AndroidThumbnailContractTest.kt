package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.playback.contract.settleForAbsence
import dev.jordond.filmstrip.playback.internal.Media3ThumbnailPlanner
import dev.jordond.filmstrip.playback.internal.Media3ThumbnailSource
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.transform.internal.seekTolerance
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * What the strip's frames have to agree with, which is the file an export writes.
 *
 * A thumbnail is a third lowering of the same edit, beside the preview and the export, and it is
 * the one nobody looks at closely. These pin it to the export the way [AndroidPixelContractTest]
 * pins the preview: both sides lower the same edit through the same `toMedia3`, one runs the chain
 * through `FrameExtractor` and the other hands it to `Transformer`.
 */
class AndroidThumbnailContractTest {
  @Test
  fun aThumbnailMatchesTheExportAtTheFramesItRendered() =
    contractTest { scope ->
      val composition = androidFixtureComposition(listOf(Brightness(DIM)))
      val source = source(scope)

      for (probe in PROBE_POSITIONS) {
        val request = ThumbnailRequest(composition, probe, FIXTURE_FRAME.height, REVISION, precise = true)
        val thumbnail = source.awaitThumbnail(request)
        try {
          thumbnail.frame().size shouldBe FIXTURE_FRAME

          val exported = androidExportFrame(composition, thumbnail.presentationTime)
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
    }

  @Test
  fun aRunMatchesTheExportAtEveryPositionItRendered() =
    contractTest { scope ->
      val composition = androidFixtureComposition(listOf(Brightness(DIM)))
      val requests =
        RUN_POSITIONS.map { ThumbnailRequest(composition, it, FIXTURE_FRAME.height, REVISION, precise = false) }

      val thumbnails = source(scope).awaitThumbnails(requests)

      try {
        thumbnails.size shouldBe requests.size
        for (thumbnail in thumbnails) {
          thumbnail.frame().size shouldBe FIXTURE_FRAME

          val exported = androidExportFrame(composition, thumbnail.presentationTime)
          assertFramesSimilar(
            expected = exported,
            actual = thumbnail.frame(),
            minSsim = ENCODED_MIN_SSIM,
            message = "a run's thumbnail and the export disagree at ${thumbnail.presentationTime}",
          )
        }
      } finally {
        thumbnails.forEach { it.image.close() }
      }
    }

  @Test
  fun aRunAfterAnEditDrawsTheNewChain() =
    contractTest { scope ->
      // The same positions twice, under edits that render differently. A run holds a decoder open
      // across its own frames, and frame extraction repeats the frame it last drew for a seek that
      // lands where it already is, so the revision is what keeps the second run off the first's
      // pixels.
      val dim = source(scope).awaitThumbnails(runRequests(DIM, REVISION))
      val lit = source(scope).awaitThumbnails(runRequests(LIT, REVISION + 1))

      try {
        // The first run is the one pinned to the file, so a pair that differs is the second having
        // drawn something of its own rather than both having drifted together.
        val exportable = androidFixtureComposition(listOf(Brightness(DIM)))
        for (thumbnail in dim) {
          assertFramesSimilar(
            expected = androidExportFrame(exportable, thumbnail.presentationTime),
            actual = thumbnail.frame(),
            minSsim = ENCODED_MIN_SSIM,
            message = "the first run and the export disagree at ${thumbnail.presentationTime}",
          )
        }

        RUN_POSITIONS.indices.forEach { at ->
          val regrade = compareFrames(dim[at].frame(), lit[at].frame()).meanAbsoluteDifference
          assertTrue(
            regrade > REGRADE_FLOOR,
            "the run at revision ${REVISION + 1} repeated the frame drawn at ${RUN_POSITIONS[at]} " +
              "under revision $REVISION: the two differ by only $regrade",
          )
        }
      } finally {
        (dim + lit).forEach { it.image.close() }
      }
    }

  @Test
  fun aCancelledRunStopsWhereItIs() =
    contractTest { scope ->
      val composition = androidFixtureComposition(listOf(Brightness(DIM)))
      val requests =
        RUN_POSITIONS.map { ThumbnailRequest(composition, it, FIXTURE_FRAME.height, REVISION, precise = false) }
      val delivered = mutableListOf<Int>()
      val handle = AtomicReference<Cancellable?>(null)

      handle.set(
        source(scope).requestThumbnails(requests) { index, result ->
          delivered += index
          (result as? ThumbnailResult.Success)?.image?.close()
          handle.get()?.cancel()
        },
      )

      // Long enough that the rest of the run would have arrived had cancelling not stopped it.
      settleForAbsence()
      delivered shouldBe listOf(0)

      // A stopped run still has to hand its extractor back, on the thread it read on. One that
      // strands instead leaves the player shared across the process held by nobody, which the next
      // run inherits, so a whole run after this one is what shows the handover happened.
      val after = source(scope).awaitThumbnails(runRequests(DIM, REVISION))
      try {
        val exportable = androidFixtureComposition(listOf(Brightness(DIM)))
        for (thumbnail in after) {
          assertFramesSimilar(
            expected = androidExportFrame(exportable, thumbnail.presentationTime),
            actual = thumbnail.frame(),
            minSsim = ENCODED_MIN_SSIM,
            message =
              "a run after a cancelled one disagrees with the export " +
                "at ${thumbnail.presentationTime}",
          )
        }
      } finally {
        after.forEach { it.image.close() }
      }
    }

  /**
   * A precise request leaves the extractor's seek parameters exact and it decodes to the frame
   * covering the position. A relaxed one snaps to the nearest sync sample, which is a whole group
   * of pictures of slack and the faster read a strip is served on.
   *
   * The position sits well inside a group of pictures, so the two land on different frames. Both
   * bounds are the shared contract every backend is held to rather than a figure measured here.
   */
  @Test
  fun aPreciseThumbnailDecodesToTheFrameARelaxedOneOnlyComesNear() =
    contractTest { scope ->
      val composition = androidFixtureComposition()

      for (precise in listOf(true, false)) {
        val request = ThumbnailRequest(composition, MID_GOP_POSITION, FIXTURE_FRAME.height, REVISION, precise)
        val thumbnail = source(scope).awaitThumbnail(request)
        try {
          withClue("precise=$precise landed at ${thumbnail.presentationTime}") {
            thumbnail.driftFrom(MID_GOP_POSITION) shouldBeLessThanOrEqualTo
              seekTolerance(precise, FIXTURE_FRAME_STEP, FIXTURE_SYNC_INTERVAL)
          }
        } finally {
          thumbnail.image.close()
        }
      }
    }

  @Test
  fun aCancelledRequestNeverDelivers() =
    contractTest { scope ->
      val composition = androidFixtureComposition(listOf(Brightness(DIM)))
      val request =
        ThumbnailRequest(composition, PROBE_POSITIONS.last(), FIXTURE_FRAME.height, REVISION, precise = true)
      val delivered = AtomicReference<ThumbnailResult?>(null)

      source(scope).requestThumbnail(request) { delivered.set(it) }.cancel()
      settle()
      delivered.get() shouldBe null

      // The same request, uncancelled, so the silence above is cancellation rather than a source
      // that cannot serve this edit at all.
      source(scope).awaitThumbnail(request).image.close()
    }

  /**
   * One run over every position, under an edit [brightness] grades and a revision of [revision].
   */
  private fun runRequests(
    brightness: Float,
    revision: Long,
  ): List<ThumbnailRequest> {
    val composition = androidFixtureComposition(listOf(Brightness(brightness)))
    return RUN_POSITIONS.map { ThumbnailRequest(composition, it, FIXTURE_FRAME.height, revision, precise = false) }
  }

  private fun source(scope: CoroutineScope): ThumbnailSource =
    Media3ThumbnailSource(
      scope = scope,
      context = contractContext(),
      planner = Media3ThumbnailPlanner(CONTRACT_COMPONENTS),
    )

  /**
   * A tile one run closed cannot empty the frame the next run is handed.
   *
   * The bitmap an extraction answers with belongs to the player media3 shares across the process,
   * which hands the same object to a later extraction, and a strip scrolled past closes the tiles
   * it no longer wants. Only overlapping runs reach that object twice, so the churn here is what
   * the case needs rather than decoration.
   */
  @Test
  fun aTileClosedByOneRunLeavesTheNextRunsPixelsReadable() =
    runTest(timeout = CHURN_TIMEOUT) {
      withContext(Dispatchers.Default) {
        val filmstrip = Filmstrip(contractContext()) { playerBackend() }
        val composition = androidFixtureComposition(listOf(Brightness(DIM)))

        repeat(CHURN_ROUNDS) {
          coroutineScope {
            List(CHURN_LANES) { lane ->
              async {
                // Cut short, which is what makes a run close the tiles still in flight, then
                // straight back in so the next extractor is built while the last is unwinding.
                filmstrip
                  .frames(composition, RUN_POSITIONS, FIXTURE_FRAME.height)
                  .take(lane + 1)
                  .collect { it.readAndClose() }
                filmstrip
                  .frames(composition, RUN_POSITIONS, FIXTURE_FRAME.height)
                  .collect { it.readAndClose() }
              }
            }.awaitAll()
          }
        }
      }
    }

  /**
   * A tile asked for inside a photo's span draws the photo, effected, rather than a blank frame.
   *
   * `FrameExtractor` builds its player with a single video renderer, so an image item has nothing
   * there to decode it and this position used to come back with no frame at all. The probe is well
   * inside the span: a reader choosing its path once per composition would still be right at a
   * boundary.
   */
  @Test
  fun aThumbnailInsideAPhotosSpanDrawsThePhoto() =
    contractTest { scope ->
      val composition = androidPhotoComposition(listOf(Brightness(DIM)))
      val request = ThumbnailRequest(composition, PHOTO_PROBE, FIXTURE_FRAME.height, REVISION, precise = false)

      val thumbnail = source(scope).awaitThumbnail(request)

      try {
        val frame = thumbnail.frame()
        frame.size shouldBe FIXTURE_FRAME

        // The photo itself. A tile agreeing with an export that also drew nothing would pass a
        // comparison and still be the bug this covers.
        frame.centre() shouldBeNothingLike BLACK

        // A still has no sync samples for a relaxed request to snap to, so it answers where asked.
        thumbnail.presentationTime shouldBe PHOTO_PROBE

        assertFramesSimilar(
          expected = androidExportFrame(composition, thumbnail.presentationTime),
          actual = frame,
          minSsim = ENCODED_MIN_SSIM,
          message = "the thumbnail and the export disagree inside the photo at $PHOTO_PROBE",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  /**
   * A strip drawn across video, photo and video, which is the run that changes path twice.
   */
  @Test
  fun aRunAcrossAPhotoDrawsEverySpan() =
    contractTest { scope ->
      val composition = androidSandwichComposition()
      val positions =
        listOf(
          MID_GOP_POSITION,
          PHOTO_PROBE,
          PHOTO_START + PHOTO_LENGTH + MID_GOP_POSITION,
        )
      val requests =
        positions.map { ThumbnailRequest(composition, it, FIXTURE_FRAME.height, REVISION, precise = false) }

      val thumbnails = source(scope).awaitThumbnails(requests)

      try {
        thumbnails.size shouldBe requests.size
        thumbnails.forEach { it.frame().size shouldBe FIXTURE_FRAME }

        val photo = thumbnails[1].frame()
        photo.centre() shouldBeCloseTo PHOTO_COLOR
        thumbnails[0].frame().centre() shouldBeNothingLike PHOTO_COLOR
        thumbnails[2].frame().centre() shouldBeNothingLike PHOTO_COLOR
      } finally {
        thumbnails.forEach { it.image.close() }
      }
    }

  private companion object {
    // Where a run starts. The suite asks twice under the same edit, so the second run moves on.
    const val REVISION = 1L

    // The fixture AndroidPixelContractTest pins the preview against, and its threshold. The effect
    // is what makes the export re-encode through the same graph rather than pass the source
    // through, so both sides of the comparison went the same way.
    const val DIM = 0.4f
    const val ENCODED_MIN_SSIM = 0.985

    // Twice DIM, and under the factor at which a channel saturates.
    const val LIT = 0.8f

    // What separates a regrade from the noise two renders of one chain differ by. The comparisons
    // above hold that noise near a mean absolute difference of one, and doubling the brightness of
    // the fixture moves every channel by tens.
    const val REGRADE_FLOOR = 10.0

    // Four positions inside the fixture, none of them on a boundary, each landing on its 30fps
    // grid. A run is what these pin, so they are the interior of the clip rather than its ends.
    val RUN_POSITIONS: List<Duration> = listOf(200, 500, 800, 1100).map { it.milliseconds }

    // Enough overlapping runs to reach a shared frame twice. Three lanes poison a tile within the
    // first round when nothing owns what it hands on.
    // What a span that drew nothing would read as, which is the failure worth telling apart from a
    // photo that really rendered.
    val BLACK: Triple<Int, Int, Int> = Triple(0, 0, 0)

    const val CHURN_ROUNDS = 4
    const val CHURN_LANES = 3
    val CHURN_TIMEOUT: Duration = 5.minutes
  }
}

/**
 * Asks for a whole run and suspends until every entry has arrived, failing the test on the first
 * one that could not be made.
 */
private suspend fun ThumbnailSource.awaitThumbnails(requests: List<ThumbnailRequest>): List<ThumbnailResult.Success> =
  suspendCancellableCoroutine { continuation ->
    val gathered = arrayOfNulls<ThumbnailResult.Success>(requests.size)
    var outstanding = requests.size
    val handle =
      requestThumbnails(requests) { index, result ->
        if (!continuation.isActive) return@requestThumbnails
        when (result) {
          is ThumbnailResult.Success -> {
            gathered[index] = result
          }
          is ThumbnailResult.Failure -> {
            continuation.resume(fail("a run's thumbnail failed: ${'$'}{result.error.message}"))
          }
        }
        outstanding--
        if (outstanding == 0) continuation.resume(gathered.map { checkNotNull(it) })
      }
    continuation.invokeOnCancellation { handle.cancel() }
  }

/**
 * Reads this frame's pixels and lets go of it, failing when what arrived was already emptied.
 */
private fun FrameResult.readAndClose() {
  val image = (this as? FrameResult.Success)?.image ?: return
  try {
    val bitmap = image.asBitmap()
    assertTrue(
      bitmap != null && !bitmap.isRecycled,
      "a tile arrived on a bitmap something else had already recycled",
    )
    assertTrue(image.toRgba8888().isNotEmpty(), "a tile arrived carrying no pixels")
  } finally {
    image.close()
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
