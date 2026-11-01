package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.assertRasterReads
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.SIZE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TEXEL
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TOLERANCE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.toCode
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.test.AppleDisplayP3
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.ColourPictures
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What this OS build's HEIC decoder does with a HEIC handed over as written whose codestream range disagrees with the
 * range its `colr nclx` names.
 *
 * Apple's HEIC decoder takes the range from the codestream, so each such still lands away from every decode its `nclx`
 * gives, which is the decode the overlay raster's rewrite gets. This fails once a build reads the range `nclx` names,
 * which says the rewrite is no longer needed there.
 *
 * ImageIO decodes JPEG XL but writes none, so the cases that read pictures ImageIO writes hold no JPEG XL. This fails
 * once a build writes one, which says those cases can take it.
 */
@OptIn(InternalFilmstripApi::class)
class EncodedPictureProfileMeasureTest {
  @Test
  fun `apple's heic decoder reads the codestream range of a heic handed over as written`() {
    val pinned = HEIC_STILLS.filter { it.nclx != null && it.nclx?.fullRange != it.vui.fullRange }
    assertTrue(
      pinned.containsAll(
        listOf(
          ColourPictures.HEIC_NCLX_BT709_FULL,
          ColourPictures.HEIC_NCLX_BT601_FULL,
          ColourPictures.HEIC_NCLX_LIMITED_VUI_FULL,
        ),
      ),
      "the HEICs whose codestream range disagrees with nclx are $pinned",
    )
    assertRasterReads()
    val misses =
      pinned.mapNotNull { heic ->
        val bytes = heic.bytes()
        assertFalse(heifWithExplicitCodestreamColour(bytes).contentEquals(bytes), "$heic was not rewritten")
        val read = assertNotNull(writtenSamples(bytes), "$heic read no samples")
        AppleStillSamples.missOrUnmeasured {
          val decoded = read.map { it.toCode() }
          val nclx = AppleStillSamples.of(heic, read).map { model -> model.map { it.toCode() } }
          val away = nclx.all { model -> decoded.indices.any { abs(decoded[it] - model[it]) > 2 * TOLERANCE } }
          if (away) {
            null
          } else {
            "$heic decoded to $decoded as written, its nclx reading $nclx, so Apple now reads the range nclx names"
          }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `imageio writes no jpeg xl`() {
    assertNull(AppleDisplayP3.picture(JPEG_XL, SIZE, TEXEL), "ImageIO now writes $JPEG_XL")
  }

  private companion object {
    const val JPEG_XL = "public.jpeg-xl"

    val HEIC_STILLS =
      ColourPictures.HEICS_WITH_SIGNAL_VARIANTS + AppleStillSamples.BT709_MATRIX_OTHER_PRIMARIES +
        ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA
  }
}
