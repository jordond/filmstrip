package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.BrowserThumbnailPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.BrowserThumbnailSource
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.transform.internal.seekTolerance
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
          minSsim = encodedMinSsim(),
          message = "the thumbnail and the export disagree at ${thumbnail.presentationTime}",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  // A thumbnail lowers through the preview's planner, which waits for the badge to decode first, so an
  // image overlay is drawn rather than refused as one the planner has not seen.
  @Test
  fun `a thumbnail under an image overlay draws the badge`() =
    contractTest {
      val badge = ImageOverlay(BADGE, Corner.TopEnd)
      val composition = webFixtureComposition(listOf(badge))
      val request =
        ThumbnailRequest(composition, PROBE_POSITIONS.first(), FIXTURE_FRAME.height, REVISION, precise = true)

      val thumbnail = source(request).awaitThumbnail(request)
      try {
        val frame = thumbnail.frame()
        val rect = badge.placedOn(frame.size, BADGE_SIZE).rectOn(frame.size)
        assertReads(Rgb.of(BADGE_COLOR), frame.around(rect), "the badge at ${thumbnail.presentationTime}")
      } finally {
        thumbnail.image.close()
      }
    }

  // The export paints the fill across a gap and draws the composition's overlays over it, so a tile
  // there reads the fill and the badge rather than whichever clip frame sits nearest.
  @Test
  fun `a thumbnail inside a leading gap draws the fill and the composition overlay`() =
    contractTest {
      val badge = ImageOverlay(BADGE, Corner.TopEnd)
      val request = ThumbnailRequest(lateComposition(badge), IN_GAP, FIXTURE_FRAME.height, REVISION, precise = true)

      val thumbnail = source(request).awaitThumbnail(request)
      try {
        thumbnail.presentationTime shouldBe IN_GAP
        assertGapDrawn(thumbnail.frame(), badge, "the thumbnail at $IN_GAP")
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun `a frame read inside a leading gap draws the fill and the composition overlay`() =
    contractTest { scope ->
      val badge = ImageOverlay(BADGE, Corner.TopEnd)
      val frame = readback(scope, lateComposition(badge), IN_GAP)

      frame.presentationTime shouldBe IN_GAP
      assertGapDrawn(frame.asTestFrame(), badge, "the frame read at $IN_GAP")
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

  // The request is cancelled once its render is inside the compositor, so the cancel has to unwind it there. The source
  // runs on a scope this test owns, so once its children have finished, the cancelled request has nothing left that
  // could still deliver.
  @Test
  fun `a cancelled request never delivers`() =
    contractTest { scope ->
      val composition = webFixtureComposition(listOf(Brightness(DIM)))
      val request =
        ThumbnailRequest(composition, PROBE_POSITIONS.last(), FIXTURE_FRAME.height, REVISION, precise = true)
      val work = Job(scope.coroutineContext[Job])
      val source = BrowserThumbnailSource(scope = scope + work, planner = BrowserThumbnailPlanner(CONTRACT_COMPONENTS))
      var delivered: ThumbnailResult? = null

      val handle = source.requestThumbnail(request) { delivered = it }
      val render = work.children.single()
      // The render is never on the dispatcher while this reads, so seeing a preview open means it is suspended inside
      // the compositor's decode.
      awaitStep("the render to open its preview") { while (source.openPreviews == 0) yield() }
      handle.cancel()
      awaitStep("the cancelled request to unwind") { work.children.forEach { it.join() } }

      render.isCancelled shouldBe true
      source.openPreviews shouldBe 0
      delivered shouldBe null

      // The same source and the same request, uncancelled, so the silence above is cancellation
      // rather than a source that cannot serve this edit at all.
      source.awaitThumbnail(request).image.close()
    }

  private fun assertGapDrawn(
    frame: TestFrame,
    badge: ImageOverlay,
    what: String,
  ) {
    frame.size shouldBe FIXTURE_FRAME
    val rect = badge.placedOn(frame.size, BADGE_SIZE).rectOn(frame.size)
    val mirrored = NormalizedRect(1f - rect.right, rect.top, 1f - rect.left, rect.bottom)
    assertReads(Rgb.of(BADGE_COLOR), frame.around(rect), "$what, the badge")
    assertReads(Rgb.of(GAP_FILL), frame.around(mirrored), "$what, the fill across from the badge")
    assertReads(Rgb.of(GAP_FILL), frame.around(CENTRE), "$what, the fill in the middle")
  }

  private suspend fun readback(
    scope: CoroutineScope,
    composition: EditComposition,
    position: Duration,
  ): ReadbackFrame {
    val engine =
      BrowserPlayerEngine(
        parent = scope,
        planner = BrowserPreviewPlanner(CONTRACT_COMPONENTS),
        config = PlayerConfig(),
      )
    var frame: ReadbackFrame? = null
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }
      frame = engine.readback.awaitFrame(position)
    }
    return frame ?: fail("no frame was read at $position")
  }

  // The fixture clip waits GAP_LENGTH on its track, so the composition opens on a gap the fill covers.
  private fun lateComposition(badge: ImageOverlay): EditComposition {
    val fixture = webFixtureComposition(listOf(badge))
    return fixture
      .withTracks(listOf(Track(fixture.clips, start = GAP_LENGTH)))
      .withFill(Fill.Solid(GAP_FILL))
  }

  private fun source(request: ThumbnailRequest): ThumbnailSource =
    createThumbnailSource(request, CONTRACT_COMPONENTS) ?: fail("no thumbnail source claimed the fixture")

  private companion object {
    // Any value. The source does not read it, and these tests never ask twice under one revision.
    const val REVISION = 1L

    // An effect over the whole composition, so a source that quietly skipped the chain would hand
    // back the source frame and fail rather than pass.
    const val DIM = 0.4f

    val GAP_LENGTH = 1.seconds

    // On the fixture's frame grid, and well clear of where the clip starts.
    val IN_GAP = 400.milliseconds

    // The badge is the other authored color, so a reading of one cannot pass as the other.
    const val GAP_FILL = AuthoredColors.MID_GREY

    // A small block in the middle of the frame, away from the badge's corner.
    val CENTRE = NormalizedRect(0.45f, 0.45f, 0.55f, 0.55f)
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
