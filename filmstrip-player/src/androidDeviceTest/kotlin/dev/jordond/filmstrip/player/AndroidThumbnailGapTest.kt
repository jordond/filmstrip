package dev.jordond.filmstrip.player

import android.graphics.Bitmap
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.color.pictureSrgbCodes
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
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailSource
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.test.writeAtomically
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A strip frame asked for inside a gap in the timeline, which has to be the frame the export writes there.
 *
 * The export paints the composition's fill across a gap and draws the composition's overlays over it, so a tile there
 * reads the fill and the overlay rather than whichever clip sits nearest. Every color is read off the fill or the
 * badge the case painted, and every position off the overlay's own placement.
 */
class AndroidThumbnailGapTest {
  @Test
  fun aThumbnailInsideALeadingGapReadsTheFill() =
    contractTest { scope ->
      val thumbnail = source(scope).awaitThumbnail(request(lateComposition(), IN_GAP, REVISION))

      try {
        val frame = thumbnail.frame()
        frame.size shouldBe FIXTURE_FRAME
        thumbnail.presentationTime shouldBe IN_GAP

        frame.colorAt(HALF, HALF) shouldBeCloseTo FILL_RGB
        frame.colorAt(CORNER_FRACTION, CORNER_FRACTION) shouldBeCloseTo FILL_RGB
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun aThumbnailInsideALeadingGapDrawsTheCompositionOverlayOverTheFill() =
    contractTest { scope ->
      val mark = ImageOverlay(ImageSource.of(badgeFile().path), Corner.BottomEnd, BADGE_MARGIN, BADGE_SCALE)
      val thumbnail = source(scope).awaitThumbnail(request(lateComposition(listOf(mark)), IN_GAP, REVISION + 1))

      try {
        val frame = thumbnail.frame()
        val rect = mark.placedOn(frame.size, BADGE).rectOn(frame.size)
        val midX = (rect.left + rect.right) / 2f
        val midY = (rect.top + rect.bottom) / 2f

        frame.colorAt(midX, midY) shouldBeCloseTo BADGE_RGB
        frame.colorAt(1f - midX, midY) shouldBeCloseTo FILL_RGB
        frame.colorAt(HALF, HALF) shouldBeCloseTo FILL_RGB
      } finally {
        thumbnail.image.close()
      }
    }

  // A lookup table profile is one Android's decoder converts when it is left in, so the tile has to read the color
  // the profile gives the picture rather than the platform's own conversion of it.
  @Test
  fun aThumbnailInsideALeadingGapDrawsATaggedOverlayInItsProfileColor() =
    contractTest { scope ->
      val picture = IccPictures.LUT_SRGB
      assertTaggedOverlayColor(scope, picture.pngOf(BADGE), picture.texel, REVISION + 3)
    }

  @Test
  fun aThumbnailInsideALeadingGapDrawsACicpOverlayInItsColor() =
    contractTest { scope ->
      val picture = ColorPictures.CICP_DISPLAY_P3
      assertTaggedOverlayColor(scope, picture.pngOf(BADGE), picture.texel, REVISION + 4)
    }

  private suspend fun assertTaggedOverlayColor(
    scope: CoroutineScope,
    png: ByteArray,
    texel: Int,
    revision: Long,
  ) {
    val mark = ImageOverlay(ImageSource.Bytes(png), Corner.BottomEnd, BADGE_MARGIN, BADGE_SCALE)
    val thumbnail = source(scope).awaitThumbnail(request(lateComposition(listOf(mark)), IN_GAP, revision))

    try {
      val frame = thumbnail.frame()
      val rect = mark.placedOn(frame.size, BADGE).rectOn(frame.size)
      val expected = pictureSrgbCodes(pictureColor(png), intArrayOf(texel)).single()

      frame.colorAt((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f) shouldBeCloseTo
        AuthoredColors.channelsOf(expected)
    } finally {
      thumbnail.image.close()
    }
  }

  @Test
  fun aThumbnailInsideTheClipAfterALeadingGapReadsTheClip() =
    contractTest { scope ->
      val composition = lateComposition()
      val position = GAP_LENGTH + MID_GOP_POSITION
      val thumbnail = source(scope).awaitThumbnail(request(composition, position, REVISION + 2))

      try {
        thumbnail.presentationTime shouldBeGreaterThanOrEqualTo GAP_LENGTH
        assertFramesSimilar(
          expected = androidExportFrame(composition, thumbnail.presentationTime),
          actual = thumbnail.frame(),
          minSsim = ENCODED_MIN_SSIM,
          message = "the thumbnail and the export disagree inside the clip at ${thumbnail.presentationTime}",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  /**
   * The bar that holds a thumbnail to the export has to turn away the frames either side of it, or a thumbnail one
   * frame out would pass. Nothing here dims the picture, since the grain is what tells two frames of the fixture apart.
   */
  @Test
  fun aThumbnailInsideTheClipFailsTheEncodedBarAgainstTheFramesEitherSideOfIt() =
    contractTest { scope ->
      val composition = lateComposition()
      val position = GAP_LENGTH + MID_GOP_POSITION
      val thumbnail = source(scope).awaitThumbnail(request(composition, position, REVISION + 5))

      try {
        val slot = outputSlotOf(thumbnail.presentationTime, FIXTURE_FRAME_RATE)
        assertFramesSimilar(
          expected = androidExportFrame(composition, thumbnail.presentationTime),
          actual = thumbnail.frame(),
          minSsim = ENCODED_MIN_SSIM,
          message = "the thumbnail and the export disagree inside the clip at ${thumbnail.presentationTime}",
        )
        for (neighbour in listOf(slot - 1, slot + 1)) {
          val neighbourFrame = androidExportFrame(composition, outputSlotTime(neighbour, FIXTURE_FRAME_RATE))
          val read = compareFrames(neighbourFrame, thumbnail.frame())
          withClue("the export's slot $neighbour against the thumbnail of slot $slot read $read") {
            read.ssim shouldBeLessThan ENCODED_MIN_SSIM
          }
        }
      } finally {
        thumbnail.image.close()
      }
    }

  private fun source(scope: CoroutineScope): ThumbnailSource =
    Media3ThumbnailSource(
      scope = scope,
      context = contractContext(),
      planner = Media3ThumbnailPlanner(CONTRACT_COMPONENTS),
    )

  private fun request(
    composition: EditComposition,
    position: Duration,
    revision: Long,
  ) = ThumbnailRequest(composition, position, FIXTURE_FRAME.height, revision, precise = true)

  // The fixture clip waits GAP_LENGTH on its track, so the video opens on a gap the fill covers.
  private fun lateComposition(effects: List<EffectSpec> = emptyList()): EditComposition =
    EditComposition(
      tracks =
        listOf(Track(listOf(Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH))), start = GAP_LENGTH)),
      effects = effects,
      fill = Fill.Solid(FILL_ARGB),
    )

  private fun badgeFile(): File {
    val file = File(contractContext().cacheDir, BADGE_NAME)
    if (file.exists()) return file
    val bitmap = Bitmap.createBitmap(BADGE.width, BADGE.height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(BADGE_ARGB)
    file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    bitmap.recycle()
    return file
  }

  private companion object {
    const val REVISION = 200L

    val GAP_LENGTH = 1.seconds
    val IN_GAP = 400.milliseconds

    // Two authored colors, far apart on red and blue.
    const val FILL_ARGB = AuthoredColors.MID_GREY
    val FILL_RGB = AuthoredColors.channelsOf(FILL_ARGB)
    const val BADGE_ARGB = AuthoredColors.MID_GREEN
    val BADGE_RGB = AuthoredColors.channelsOf(BADGE_ARGB)

    const val BADGE_NAME = "filmstrip-player-badge.png"
    val BADGE = Size(64, 64)
    const val BADGE_MARGIN = 0.04f
    const val BADGE_SCALE = 0.25f
    const val PNG_QUALITY = 100

    // Close to the top start corner, clear of the edge row a resize can blend.
    const val CORNER_FRACTION = 0.05f
    const val HALF = 0.5f
  }
}

/**
 * The red, green and blue codes at a point given as a fraction of this frame's width and height.
 */
private fun TestFrame.colorAt(
  fx: Float,
  fy: Float,
): List<Int> {
  val x = (fx * size.width).toInt().coerceIn(0, size.width - 1)
  val y = (fy * size.height).toInt().coerceIn(0, size.height - 1)
  val offset = (y * size.width + x) * CHANNELS
  return List(RGB) { pixels[offset + it].toInt() and BYTE_MASK }
}

/**
 * Holds every channel within [AuthoredColors.TOLERANCE] of [expected], so a reading of the nearest clip, or a color
 * managed move of the authored codes, fails.
 */
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
