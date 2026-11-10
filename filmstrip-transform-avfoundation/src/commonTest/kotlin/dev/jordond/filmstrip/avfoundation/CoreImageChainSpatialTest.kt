package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.internal.chain.CoreImageChain
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A blur drawn through a real Apple chain at each scope, read against the shared reference.
 *
 * The edit resolves against a written clip, and the chain draws a frame built here in its place, so every read is of
 * exact codes rather than of an encoder's.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreImageChainSpatialTest {
  @Test
  fun `a composition blur on a contained clip keeps the fill in the bars and the picture at its edge`() =
    runTest(timeout = TIMEOUT) {
      val plain = drawn(contained(clipEffects = emptyList(), compositionEffects = emptyList()))
      val blurred = drawn(contained(clipEffects = emptyList(), compositionEffects = listOf(GaussianBlur(SIGMA))))
      val width = plain.width
      val left = plain.pictureLeft()
      val alpha = DoubleArray(width * CLIP_HEIGHT) { if (it % width in left until left + CLIP_WIDTH) 1.0 else 0.0 }
      // Each row of the picture is one level, so its middle column is every column of it.
      val picture = DoubleArray(width * CLIP_HEIGHT) { plain.red[it / width * width + width / 2].toDouble() }
      val reference = blurPlane(picture, alpha, width, CLIP_HEIGHT, SIGMA.toDouble())

      var worst = 0.0
      for (index in reference.indices) {
        if (alpha[index] == 0.0) {
          withClue("bar pixel ${index % width} ${index / width}") { blurred.red[index] shouldBe plain.red[index] }
        } else {
          worst = maxOf(worst, abs(blurred.red[index] - reference[index]))
        }
      }
      // A row on the blur's ramp, read at the picture's edge and in its middle.
      val row = (CLIP_HEIGHT / 2 - RAMP_ROWS) * width
      withClue("the picture's edge read ${blurred.red[row + left]} and its middle ${blurred.red[row + width / 2]}") {
        abs(blurred.red[row + left] - blurred.red[row + width / 2]) * FULL shouldBeLessThanOrEqualTo BLUR_CODES
      }
      withClue("the drawn blur is ${worst * FULL} codes off the shared reference at its worst picture pixel") {
        worst * FULL shouldBeLessThanOrEqualTo BLUR_CODES
      }
    }

  @Test
  fun `a clip blur spans the clip's own frame and leaves the bars as the fill`() =
    runTest(timeout = TIMEOUT) {
      val plain = drawn(contained(clipEffects = emptyList(), compositionEffects = emptyList()))
      val blurred = drawn(contained(clipEffects = listOf(GaussianBlur(SIGMA)), compositionEffects = emptyList()))
      val width = plain.width
      val left = plain.pictureLeft()
      val picture = DoubleArray(CLIP_WIDTH * CLIP_HEIGHT) { plain.red[it / CLIP_WIDTH * width + width / 2].toDouble() }
      val reference = blurPlane(picture, DoubleArray(picture.size) { 1.0 }, CLIP_WIDTH, CLIP_HEIGHT, SIGMA.toDouble())

      var worst = 0.0
      for (index in 0 until width * CLIP_HEIGHT) {
        val column = index % width
        val row = index / width
        if (column !in left until left + CLIP_WIDTH) {
          withClue("bar pixel $column $row") { blurred.red[index] shouldBe plain.red[index] }
        } else {
          worst = maxOf(worst, abs(blurred.red[index] - reference[row * CLIP_WIDTH + column - left]))
        }
      }
      withClue("the drawn blur is ${worst * FULL} codes off the shared reference on the clip's frame") {
        worst * FULL shouldBeLessThanOrEqualTo BLUR_CODES
      }
    }

  @Test
  fun `an overlay at the blur's scope stays sharp`() =
    runTest(timeout = TIMEOUT) {
      val overlay =
        ImageOverlay(ImageSource.ofBytes(OverlayPixels.pngOf(Size(4, 4), OVERLAY_ARGB)), Corner.TopStart, 0f, 0.25f)
      val bare = drawn(Composition(listOf(Track(listOf(Clip(clip()))))))
      val sharp = drawn(Composition(listOf(Track(listOf(Clip(clip(), effects = listOf(overlay)))))))
      val blurred =
        drawn(Composition(listOf(Track(listOf(Clip(clip(), effects = listOf(GaussianBlur(SIGMA), overlay)))))))
      // Where the overlay draws opaque, so no picture shows through it. Those beside its scaled up edge, where a blur
      // of the overlay would pull it toward the picture, are the reads that matter.
      val inside = sharp.red.indices.filter { abs(sharp.red[it] - OVERLAY_CODE) < OPAQUE_OVERLAY }
      inside.size shouldBeGreaterThan 0
      val worst = inside.maxOf { abs(blurred.red[it] - sharp.red[it]) }
      withClue("the overlay is ${worst * FULL} codes off its sharp draw at its worst pixel") {
        worst * FULL shouldBeLessThanOrEqualTo BLUR_CODES
      }
    }

  // The clip contained in a layout twice its width, with bars of the fill either side at its own size.
  private suspend fun contained(
    clipEffects: List<EffectSpec>,
    compositionEffects: List<EffectSpec>,
  ): Composition =
    Composition(
      listOf(Track(listOf(Clip(clip(), effects = clipEffects)))),
      listOf<EffectSpec>(Crop(WIDE, Fit.Contain)) + compositionEffects,
      fill = Fill.Solid(FILL_ARGB),
    )

  // Where the picture starts in a frame of [contained], with the frame's size checked on the way.
  private fun Pixels.pictureLeft(): Int {
    height shouldBe CLIP_HEIGHT
    width shouldBe 2 * CLIP_WIDTH
    return (width - CLIP_WIDTH) / 2
  }

  private suspend fun drawn(edit: Composition): Pixels {
    val chain: CoreImageChain =
      withContext(Dispatchers.Default) {
        avFoundationExportEngine(COMPONENTS)
          .resolve(edit, ExportSpec())
          .shouldBeInstanceOf<ResolveResult.Resolved>()
          .composition
          .toAvComposition()
      }.chain.shouldNotBeNull()
    val image = chain.drawn(chain.current, source(), MIDDLE)
    chain.failure.shouldBeNull()
    val (width, height) = image.extent.useContents { size.width.toInt() to size.height.toInt() }
    return Pixels(width, height, codesOf(image, width, height))
  }

  private suspend fun clip(): MediaSource {
    val path = clipPath()
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
      withContext(Dispatchers.Default) {
        writeTaggedBars(
          path,
          FRAMES,
          AVVideoColorPrimaries_ITU_R_709_2.orEmpty(),
          AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty(),
        )
      }
    }
    return MediaSource.of(path)
  }

  private fun clipPath(): String = NSTemporaryDirectory() + "filmstrip-apple-chain-spatial.mp4"

  // The red codes of [image] in its BT.709 buffer, rows from the top.
  private fun codesOf(
    image: CIImage,
    width: Int,
    height: Int,
  ): FloatArray {
    val values = FloatArray(width * height * CHANNELS)
    val space = ColorSpace.Bt709.createCodeSpace()
    try {
      values.usePinned { pinned ->
        CIContext().render(
          image,
          toBitmap = pinned.addressOf(0),
          rowBytes = (width * CHANNELS * Float.SIZE_BYTES).toLong(),
          bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
          format = kCIFormatRGBAf,
          colorSpace = space,
        )
      }
    } finally {
      CGColorSpaceRelease(space)
    }
    return FloatArray(width * height) { values[it * CHANNELS] }
  }

  // The frame the chain draws in place of the decoded one: the clip's size, light on its top half and dark on its
  // bottom, in BT.709 codes.
  private fun source(): CIImage {
    val values = FloatArray(CLIP_WIDTH * CLIP_HEIGHT * CHANNELS)
    for (index in 0 until CLIP_WIDTH * CLIP_HEIGHT) {
      val value = if (index / CLIP_WIDTH < CLIP_HEIGHT / 2) LIGHT else DARK
      values[index * CHANNELS] = value
      values[index * CHANNELS + 1] = value
      values[index * CHANNELS + 2] = value
      values[index * CHANNELS + 3] = 1f
    }
    val space = ColorSpace.Bt709.createCodeSpace()
    try {
      val data =
        values.usePinned { pinned ->
          NSData.create(bytes = pinned.addressOf(0), length = (values.size * Float.SIZE_BYTES).convert())
        }
      return CIImage.imageWithBitmapData(
        data,
        bytesPerRow = (CLIP_WIDTH * CHANNELS * Float.SIZE_BYTES).convert(),
        size = CGSizeMake(CLIP_WIDTH.toDouble(), CLIP_HEIGHT.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    } finally {
      CGColorSpaceRelease(space)
    }
  }

  private class Pixels(
    val width: Int,
    val height: Int,
    val red: FloatArray,
  )

  private companion object {
    val TIMEOUT = 2.minutes
    val MIDDLE = 250.milliseconds
    val COMPONENTS = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
    const val FRAMES = 15
    const val CHANNELS = 4
    const val FULL = 255.0

    // The size writeTaggedBars writes, which the drawn frame stands in for.
    const val CLIP_WIDTH = 640
    const val CLIP_HEIGHT = 360

    const val SIGMA = 0.05f
    val WIDE = AspectRatio(32, 9)

    // Rows above the middle, where the blur of the edge there is part way along its ramp.
    const val RAMP_ROWS = 10
    const val LIGHT = 0.85f
    const val DARK = 0.15f
    const val FILL_ARGB = 0xFF3080C0.toInt()
    const val OVERLAY_ARGB = 0xFF202020.toInt()

    const val OVERLAY_CODE = 0x20 / 255f

    // A pixel this close to the overlay's own code shows none of the picture under it.
    const val OPAQUE_OVERLAY = 1e-3f

    // Core Image's Gaussian against the plan's box, taps and linear read back up.
    const val BLUR_CODES = 2.0
  }
}
