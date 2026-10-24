package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.media.ycbcrReading
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicIdentityPictures
import dev.jordond.filmstrip.test.HeicSubsampledPictures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Holds [imageIoLanding] to the way [AppleStillSamples.landing] says each still is handed to ImageIO, before any of it
 * is decoded.
 *
 * An eight or ten-bit 4:2:0 or 4:2:2 still under each matrix from 0 to 15, at both ranges, is decoded as it is where
 * ImageIO reads its matrix, restated to the nearest matrix it reads and degraded by [unreadMatrix] within the bound,
 * and refused past it or where core refuses its reading. An overlay on a degraded one resolves degraded with that one
 * cause. A twelve-bit 4:4:4 still under the identity matrix lands past the bound, since only eight and ten-bit stills
 * are read through their stored samples, and a still whose codestream cannot be restated is refused with
 * [UNRESTATED] on each route that restates.
 */
@OptIn(InternalFilmstripApi::class)
class ImageIoLandingTest {
  @Test
  fun `a subsampled still lands through its own matrix or the nearest one apple's decoder reads`() {
    val misses =
      HeicSubsampledPictures.ALL.flatMap { still ->
        tags().mapNotNull { nclx -> subsampledMiss(still, nclx) }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `an overlay on a subsampled still under an unread matrix resolves degraded naming the landing`() {
    val misses =
      HeicSubsampledPictures.ALL.flatMap { still ->
        listOf(FCC, SMPTE_240M).flatMap { matrix ->
          listOf(true, false).mapNotNull { full ->
            val nclx = CicpTag(1, 13, matrix, full)
            val landed = checkNotNull(AppleStillSamples.landing(nclx, unsubsampled = false, still.bitDepth))
            val expected = "degraded: ${listOf(unreadMatrix(landed).message)}"
            val resolved = describe(overlayResolution(still.bytes(nclx)))
            if (resolved == expected) null else "$still with nclx $nclx resolved $resolved, where it is $expected"
          }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `a twelve bit 4 4 4 still under the identity matrix is refused past the bound`() {
    for (full in listOf(true, false)) {
      val nclx = CicpTag(1, 13, IDENTITY, full)
      val nearest = checkNotNull(ycbcrReading(IDENTITY, 1, 13).nearestReadable(IMAGE_IO_MATRICES - IDENTITY))

      val landing = imageIoLanding(HeicIdentityPictures.twelveBitBytes(nclx))

      assertFalse(nearest.withinBound, "the identity matrix lands within the bound through ${nearest.matrix}")
      assertEquals(pastBound(nearest), assertIs<ImageIoLanding.Refused>(landing, "nclx $nclx landed").message)
      assertEquals(refusalOf(nclx, unsubsampled = true, TWELVE_BITS), landing.message)
    }
  }

  @Test
  fun `an eight and ten bit 4 4 4 still under the identity matrix at full range is decoded as it is`() {
    val nclx = CicpTag(1, 13, IDENTITY, true)
    for (bytes in listOf(HeicIdentityPictures.bytes(nclx), HeicIdentityPictures.tenBitBytes(nclx))) {
      val landing = assertIs<ImageIoLanding.Decoded>(imageIoLanding(bytes))

      assertEquals(emptyList(), landing.limits)
      assertEquals(IDENTITY, pictureYcbcrCoding(landing.bytes)?.matrix)
    }
  }

  @Test
  fun `a still whose codestream cannot be restated is refused on each route that restates`() {
    val stills =
      listOf(
        "the evaluated 4:4:4 still" to HeicIdentityPictures.bytes(CicpTag(1, 13, SMPTE_240M, true)),
        "the exact restate" to HeicSubsampledPictures.EIGHT_BIT_420.bytes(CicpTag(1, 13, CHROMATICITY_DERIVED, true)),
        "the nearest restate" to HeicSubsampledPictures.EIGHT_BIT_420.bytes(CicpTag(1, 13, SMPTE_240M, true)),
      )
    for ((name, bytes) in stills) {
      val landing = imageIoLanding(bytes + MOOV)

      assertEquals(UNRESTATED, assertIs<ImageIoLanding.Refused>(landing, "$name with a moov box landed").message)
      assertTrue(imageIoLanding(bytes) !is ImageIoLanding.Refused, "$name without a moov box was refused")
    }
  }

  private fun subsampledMiss(
    still: HeicSubsampledPictures.Still,
    nclx: CicpTag,
  ): String? {
    val landing = imageIoLanding(still.bytes(nclx))
    val landed = AppleStillSamples.landing(nclx, unsubsampled = false, still.bitDepth)
    val name = "$still with nclx $nclx"
    return when {
      landed == null -> {
        val refusal = refusalOf(nclx, unsubsampled = false, still.bitDepth)
        val message = (landing as? ImageIoLanding.Refused)?.message
        if (message == refusal) null else "$name came to $landing, where it is refused: $refusal"
      }
      landing !is ImageIoLanding.Decoded -> {
        "$name came to $landing, where it is decoded through matrix ${landed.matrix}"
      }
      landed.exact -> {
        if (landing.limits.isEmpty()) null else "$name was degraded, where matrix ${landed.matrix} reads it exactly"
      }
      else -> {
        val matrix = pictureYcbcrCoding(landing.bytes)?.matrix
        val limits = landing.limits.map { it.message }
        val expected = listOf(unreadMatrix(landed).message)
        when {
          matrix != landed.matrix -> "$name was restated to matrix $matrix, not ${landed.matrix}"
          limits != expected -> "$name was degraded by $limits, where it is $expected"
          else -> null
        }
      }
    }
  }

  private fun tags(): List<CicpTag> =
    (0..15).flatMap { matrix -> listOf(true, false).map { CicpTag(1, 13, matrix, it) } }

  private companion object {
    const val IDENTITY = 0
    const val FCC = 4
    const val SMPTE_240M = 7
    const val CHROMATICITY_DERIVED = 12
    const val TWELVE_BITS = 12

    // An empty moov box, which the HEIF rewrite does not understand, so it restates nothing.
    val MOOV = byteArrayOf(0, 0, 0, 8) + "moov".encodeToByteArray()
  }
}
