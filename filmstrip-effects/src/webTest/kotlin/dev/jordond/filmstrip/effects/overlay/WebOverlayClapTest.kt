@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureCropOf
import dev.jordond.filmstrip.internal.pictureOrientationOf
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PictureCrop
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.heifWithoutOrientation
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.OrientedPictures
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * AVIFs with a `clap` on their primary item, drawn by the browser resolver at the crop core reads.
 *
 * Chrome is handed each picture with its `clap` dropped, so it decodes the whole stored frame and the resolver cuts
 * it. Each overlay holds the texels of the same picture without a `clap`, cut at [pictureCropOf]'s rectangle, which
 * core's still measure sizes the picture by.
 */
@OptIn(InternalFilmstripApi::class)
class WebOverlayClapTest {
  @Test
  fun aCentredAnOffsetAndACornerClapDrawTheTexelsCoreCropsTo() =
    runTest {
      val crops =
        listOf(
          "centred" to PictureCrop(6, 10, Size(20, 12)),
          "offset" to PictureCrop(1, 14, Size(20, 12)),
          "corner" to PictureCrop(0, 0, Size(20, 12)),
        )
      for ((name, nclx) in ROUTES) {
        val still = MatrixAvif.EIGHT_BIT_444.bytes(nclx)
        val full = drawnTexels(resolvedOverlay(still))
        for ((clap, expected) in crops) {
          val clapped = OrientedPictures.heifWithProperties(still, listOf(CLAPS.getValue(clap)))
          assertEquals(expected, pictureCropOf(clapped), "$name $clap crop")

          val overlay = resolvedOverlay(clapped)

          assertEquals(name == "evaluated", sourceOf(overlay).samples != null, "$name $clap route")
          assertEquals(expected.size, overlay.size, "$name $clap size")
          assertContentEquals(full.cropped(expected), drawnTexels(overlay), "$name $clap texels")
        }
      }
    }

  // A clap listed ahead of a quarter turn crops the stored picture, so the overlay holds the same cut and the
  // compositor turns it.
  @Test
  fun aClapAheadOfATurnIsCutBeforeTheTurn() =
    runTest {
      val still = MatrixAvif.EIGHT_BIT_444.bytes(ROUTES.first().second)
      val full = drawnTexels(resolvedOverlay(still))
      val clapped = OrientedPictures.heifWithProperties(still, listOf(CLAPS.getValue("offset"), QUARTER_TURN))
      val crop = assertNotNull(pictureCropOf(clapped))

      val overlay = resolvedOverlay(clapped)

      assertEquals(pictureOrientationOf(clapped), overlay.orientation)
      assertEquals(crop.size, overlay.size)
      assertContentEquals(full.cropped(crop), drawnTexels(overlay))
    }

  // A clap listed after a quarter turn crops the turned frame. Core carries it back to a rectangle of the stored
  // picture, so the overlay holds that cut and the compositor turns it, where the drop used to refuse the file.
  @Test
  fun aClapAfterATurnIsCutAtTheStoredRectangleCoreReads() =
    runTest {
      val still = MatrixAvif.EIGHT_BIT_444.bytes(ROUTES.first().second)
      val full = drawnTexels(resolvedOverlay(still))
      val clapped = OrientedPictures.heifWithProperties(still, listOf(QUARTER_TURN, CLAPS.getValue("offset")))
      val crop = assertNotNull(pictureCropOf(clapped))

      val overlay = resolvedOverlay(clapped)

      assertEquals(pictureOrientationOf(clapped), overlay.orientation)
      assertEquals(crop.size, overlay.size)
      assertContentEquals(full.cropped(crop), drawnTexels(overlay))
    }

  // Chrome decodes the bytes the drop hands it at the whole stored frame, whatever clap the file lists.
  @Test
  fun theBrowserDecodesTheDroppedBytesUncropped() =
    runTest {
      val still = MatrixAvif.EIGHT_BIT_444.bytes(ROUTES.first().second)
      val stored = resolvedOverlay(still).size
      for (clap in CLAPS.values) {
        val clapped = OrientedPictures.heifWithProperties(still, listOf(clap))
        val handed = assertIs<UnturnedHeif.Dropped>(heifWithoutOrientation(clapped)).bytes

        val bitmap = bitmapOf(handed)

        assertEquals(stored, Size(bitmap.width, bitmap.height))
      }
    }

  // The texels the overlay holds, its samples when it carries them and its bitmap's codes otherwise, with their size.
  private fun drawnTexels(overlay: WebGlOverlay): Texels {
    val source = sourceOf(overlay)
    val samples = source.samples
    if (samples != null) return Texels(overlay.size, IntArray(samples.size) { samples[it].toInt() })
    val codes = assertNotNull(codesOf(source.image.unsafeCast<ImageBitmap>()))
    return Texels(overlay.size, IntArray(codes.size) { codes[it].toInt() and BYTE })
  }

  private fun sourceOf(overlay: WebGlOverlay): OverlaySource.Image = assertIs<OverlaySource.Image>(overlay.source)

  private suspend fun resolvedOverlay(bytes: ByteArray): WebGlOverlay {
    val image = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopEnd)
    val resolver = BuiltInEffectResolver()
    resolver.preload(EditComposition(listOf(Track(listOf(Clip(MediaSource.ofUri(CLIP), effects = listOf(image)))))))
    val resolution = assertIs<EffectResolution.Resolved>(resolver.resolve(image, CAPABILITIES, ATTRIBUTES))
    return assertNotNull(resolution.effect.overlay)
  }

  // Four channels a texel, row by row.
  private class Texels(
    val size: Size,
    val channels: IntArray,
  ) {
    fun cropped(crop: PictureCrop): Texels {
      val width = crop.size.width
      val kept =
        IntArray(width * crop.size.height * CHANNELS) {
          val texel = it / CHANNELS
          val row = crop.top + texel / width
          val column = crop.left + texel % width
          channels[(row * size.width + column) * CHANNELS + it % CHANNELS]
        }
      return Texels(crop.size, kept)
    }
  }

  private fun assertContentEquals(
    expected: Texels,
    actual: Texels,
    message: String? = null,
  ) {
    assertEquals(expected.size, actual.size, message)
    assertContentEquals(expected.channels, actual.channels, message)
  }

  private companion object {
    const val BYTE = 0xFF
    const val CHANNELS = 4
    const val CLIP = "blob:clip"

    // The route Chrome decodes through its own matrix, BT.709, and the one core's evaluator reads, BT.2020 constant
    // luminance.
    val ROUTES = listOf("decoded" to CicpTag(1, 13, 1, true), "evaluated" to CicpTag(9, 13, 10, true))

    val CLAPS =
      mapOf(
        "centred" to OrientedPictures.cleanAperture(20, 12),
        "offset" to
          OrientedPictures.cleanAperture(20, 12, horizontalOffset = -9, verticalOffset = 7, offsetDenominator = 2),
        "corner" to OrientedPictures.cleanAperture(20, 12, horizontalOffset = -6, verticalOffset = -10),
      )
    val QUARTER_TURN = OrientedPictures.quarterTurn(1)

    val FRAME = Size(1280, 720)
    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.WebGl,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 4_096,
        features = emptySet(),
      )
    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 10.seconds),
      )
  }
}
