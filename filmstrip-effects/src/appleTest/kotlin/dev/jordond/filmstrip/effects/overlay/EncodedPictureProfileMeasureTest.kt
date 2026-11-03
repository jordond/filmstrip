package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.assertRasterReads
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.SIZE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TEXEL
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TOLERANCE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.toCode
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.test.AppleDisplayP3
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.ColorPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test

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
class EncodedPictureProfileMeasureTest {
  @Test
  fun `apple's heic decoder reads the codestream range of a heic handed over as written`() {
    val pinned = HEIC_STILLS.filter { it.nclx != null && it.nclx?.fullRange != it.vui.fullRange }
    withClue("the HEICs whose codestream range disagrees with nclx are $pinned") {
      pinned.containsAll(
        listOf(
          ColorPictures.HEIC_NCLX_BT709_FULL,
          ColorPictures.HEIC_NCLX_BT601_FULL,
          ColorPictures.HEIC_NCLX_LIMITED_VUI_FULL,
        ),
      ) shouldBe true
    }
    assertRasterReads()
    val misses =
      pinned.mapNotNull { heic ->
        val bytes = heic.bytes()
        val stated = heifWithExplicitCodestreamColor(bytes).shouldBeInstanceOf<UnturnedHeif.Handed>().bytes
        withClue("$heic was not rewritten") { stated.contentEquals(bytes) shouldBe false }
        val read = withClue("$heic read no samples") { writtenSamples(bytes).shouldNotBeNull() }
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

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun `imageio writes no jpeg xl`() {
    withClue("ImageIO now writes $JPEG_XL") { AppleDisplayP3.picture(JPEG_XL, SIZE, TEXEL).shouldBeNull() }
  }

  private companion object {
    const val JPEG_XL = "public.jpeg-xl"

    val HEIC_STILLS =
      ColorPictures.HEICS_WITH_SIGNAL_VARIANTS + AppleStillSamples.BT709_MATRIX_OTHER_PRIMARIES +
        ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA
  }
}
