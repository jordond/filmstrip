package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A strip frame and a frame read asked for inside a gap in the timeline, both of which have to be the frame the export
 * writes there.
 *
 * The export paints the composition's fill across a gap and draws the composition's overlays over it, so a frame there
 * reads the fill and the overlay rather than whichever clip sits nearest. Every color is read off the fill or the
 * badge the case painted, and every position off the overlay's own placement.
 *
 * An overlay composites in RGB and the frame goes back to 4:2:0 for the encoder, which can cost a fill a code of luma
 * in ffmpeg's scaler. That fits the authored tolerance, so a fill under an overlay reads both the codes it was painted
 * with and the export's own, and a shift in both fails.
 */
class JvmThumbnailGapMediaTest {
  @Test
  fun `a thumbnail inside a leading gap reads the fill as its own codes`() =
    contractTest {
      AuthoredColors.COLORS.forEachIndexed { index, color ->
        val composition = lateComposition(fill = color)
        val request = request(composition, IN_GAP, REVISION + index)
        val thumbnail = source(request).awaitThumbnail(request)

        try {
          val frame = thumbnail.frame()
          frame.size shouldBe FIXTURE_FRAME
          thumbnail.presentationTime shouldBe IN_GAP

          withClue("the gap filled with ${AuthoredColors.channelsOf(color)}") {
            frame.colorAt(HALF, HALF) shouldBeCloseTo AuthoredColors.channelsOf(color)
            frame.colorAt(CORNER_FRACTION, CORNER_FRACTION) shouldBeCloseTo AuthoredColors.channelsOf(color)
          }
        } finally {
          thumbnail.image.close()
        }
      }
    }

  @Test
  fun `a thumbnail inside a leading gap draws the composition overlay over the fill`() =
    contractTest {
      val mark = badge()
      val composition = lateComposition(listOf(mark))
      val request = request(composition, IN_GAP, REVISION)
      val thumbnail = source(request).awaitThumbnail(request)

      try {
        thumbnail.frame().assertBadgeOverFill(mark, composition)
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun `a thumbnail inside the clip after a leading gap reads the clip`() =
    contractTest {
      val composition = lateComposition()
      val request = request(composition, GAP_LENGTH + MID_GOP_POSITION, REVISION)
      val thumbnail = source(request).awaitThumbnail(request)

      try {
        thumbnail.presentationTime shouldBeGreaterThanOrEqualTo GAP_LENGTH
        assertFramesSimilar(
          expected = jvmExportFrame(composition, thumbnail.presentationTime),
          actual = thumbnail.frame(),
          minSsim = ENCODED_MIN_SSIM,
          message = "the thumbnail and the export disagree inside the clip at ${thumbnail.presentationTime}",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun `a frame read inside a leading gap draws the composition overlay over the fill`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val mark = badge()
      val composition = lateComposition(listOf(mark))

      withEngine(subject.engine) { recorder ->
        subject.engine
          .awaitComposition(composition)
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }

        val read = subject.engine.readback.awaitFrame(IN_GAP)
        read.presentationTime shouldBe IN_GAP
        TestFrame(read.pixels, read.size).assertBadgeOverFill(mark, composition)
      }
    }

  /**
   * The bar that holds a thumbnail to the export has to turn away the frames either side of it, or a thumbnail one
   * frame out would pass. Nothing here dims the picture, since the grain is what tells two frames of the fixture apart.
   */
  @Test
  fun `a thumbnail inside the clip fails the encoded bar against the frames either side of it`() =
    contractTest {
      val composition = lateComposition()
      val request = request(composition, GAP_LENGTH + MID_GOP_POSITION, REVISION)
      val thumbnail = source(request).awaitThumbnail(request)

      try {
        val slot = outputSlotOf(thumbnail.presentationTime, FIXTURE_RATE)
        assertFramesSimilar(
          expected = jvmExportFrame(composition, thumbnail.presentationTime),
          actual = thumbnail.frame(),
          minSsim = ENCODED_MIN_SSIM,
          message = "the thumbnail and the export disagree inside the clip at ${thumbnail.presentationTime}",
        )
        for (neighbour in listOf(slot - 1, slot + 1)) {
          val neighbourFrame = jvmExportFrame(composition, outputSlotTime(neighbour, FIXTURE_RATE))
          val read = compareFrames(neighbourFrame, thumbnail.frame())
          withClue("the export's slot $neighbour against the thumbnail of slot $slot read $read") {
            read.ssim shouldBeLessThan ENCODED_MIN_SSIM
          }
        }
      } finally {
        thumbnail.image.close()
      }
    }

  private fun source(request: ThumbnailRequest): ThumbnailSource =
    createThumbnailSource(request, CONTRACT_COMPONENTS) ?: fail("no thumbnail source claimed the fixture")

  private fun request(
    composition: EditComposition,
    position: Duration,
    revision: Long,
  ) = ThumbnailRequest(composition, position, FIXTURE_FRAME.height, revision, precise = true)

  // The fixture clip waits GAP_LENGTH on its track, so the video opens on a gap the fill covers.
  private fun lateComposition(
    effects: List<EffectSpec> = emptyList(),
    fill: Int = FILL_ARGB,
  ): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(
            listOf(Clip(MediaSource.of(jvmFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH))),
            start = GAP_LENGTH,
          ),
        ),
      effects = effects,
      fill = Fill.Solid(fill),
    )

  private fun badge(): ImageOverlay =
    ImageOverlay(ImageSource.ofBytes(badgePng), Corner.BottomEnd, BADGE_MARGIN, BADGE_SCALE)

  // The badge's middle reads its own color, the point mirrored across the frame reads the fill, and so does the centre.
  private suspend fun TestFrame.assertBadgeOverFill(
    mark: ImageOverlay,
    composition: EditComposition,
  ) {
    val rect = mark.placedOn(size, BADGE).rectOn(size)
    val midX = (rect.left + rect.right) / 2f
    val midY = (rect.top + rect.bottom) / 2f
    val written = jvmExportFrame(composition, IN_GAP)

    withClue("the badge") { colorAt(midX, midY) shouldBeCloseTo AuthoredColors.channelsOf(BADGE_ARGB) }
    withClue("the fill beside the badge") { assertFillAt(1f - midX, midY, written) }
    withClue("the fill at the centre") { assertFillAt(HALF, HALF, written) }
  }

  private fun TestFrame.assertFillAt(
    fx: Float,
    fy: Float,
    written: TestFrame,
  ) {
    val read = colorAt(fx, fy)
    read shouldBeCloseTo written.colorAt(fx, fy)
    read shouldBeCloseTo AuthoredColors.channelsOf(FILL_ARGB)
  }

  private companion object {
    const val REVISION = 300L

    val GAP_LENGTH = 1.seconds
    val IN_GAP = 400.milliseconds

    // Two authored colors, which sit apart on red and blue.
    const val FILL_ARGB = AuthoredColors.MID_GREEN
    const val BADGE_ARGB = AuthoredColors.MID_GREY

    val BADGE = Size(64, 64)
    const val BADGE_MARGIN = 0.04f
    const val BADGE_SCALE = 0.25f

    // Close to the top start corner, clear of the edge row a resize can blend.
    const val CORNER_FRACTION = 0.05f
    const val HALF = 0.5f

    val badgePng: ByteArray by lazy {
      val image = BufferedImage(BADGE.width, BADGE.height, BufferedImage.TYPE_INT_ARGB)
      val graphics = image.createGraphics()
      graphics.color = Color(BADGE_ARGB, true)
      graphics.fillRect(0, 0, BADGE.width, BADGE.height)
      graphics.dispose()
      ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
  }
}

// The red, green and blue codes at a point given as a fraction of this frame's width and height.
private fun TestFrame.colorAt(
  fx: Float,
  fy: Float,
): List<Int> {
  val x = (fx * size.width).toInt().coerceIn(0, size.width - 1)
  val y = (fy * size.height).toInt().coerceIn(0, size.height - 1)
  val offset = (y * size.width + x) * CHANNELS
  return List(RGB) { pixels[offset + it].toInt() and BYTE_MASK }
}

// Every channel within the tolerance an authored color is held to, so a reading that is the nearest clip, or a
// color-managed move of the authored codes, fails.
private infix fun List<Int>.shouldBeCloseTo(expected: List<Int>) {
  val within = zip(expected).all { (read, want) -> abs(read - want) <= AuthoredColors.TOLERANCE }
  if (!within) fail("read $this, expected $expected within ${AuthoredColors.TOLERANCE}")
}

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

private fun ThumbnailResult.Success.frame(): TestFrame =
  TestFrame(image.toRgba8888(), Size(image.widthPx, image.heightPx))

private const val CHANNELS = 4
private const val RGB = 3
private const val BYTE_MASK = 0xFF
