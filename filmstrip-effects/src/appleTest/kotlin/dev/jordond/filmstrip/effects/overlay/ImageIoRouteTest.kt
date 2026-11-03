package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithoutOrientation
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicIdentityPictures
import dev.jordond.filmstrip.test.HeicSubsampledPictures
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Holds [imageIoRouteOf] to the way [AppleStillSamples.decoderMatrix] says each still is handed to ImageIO, before any
 * of it is decoded.
 *
 * An eight or ten-bit 4:2:0 or 4:2:2 still under each matrix from 0 to 15, at both ranges, is decoded as it is where
 * ImageIO reads its matrix, restated to the nearest matrix it reads and degraded by [unreadMatrix] within the bound,
 * and refused past it or where core refuses its reading. An overlay on a degraded one resolves degraded with that one
 * cause. A twelve-bit 4:4:4 still under the identity matrix lands past the bound, since only eight and ten-bit stills
 * are read through their stored samples, and a still whose codestream cannot be restated is refused with
 * [UNRESTATED] on each route that restates. A tagged still whose `irot` and `imir` drop core refuses is refused with
 * core's own sentence, whether it is handed over as it states its color or restated.
 */
class ImageIoRouteTest {
  @Test
  fun `a subsampled still lands through its own matrix or the nearest one apple's decoder reads`() {
    val misses =
      HeicSubsampledPictures.ALL.flatMap { still ->
        tags().mapNotNull { nclx -> subsampledMiss(still, nclx) }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun `an overlay on a subsampled still under an unread matrix resolves degraded naming the landing`() {
    val misses =
      HeicSubsampledPictures.ALL.flatMap { still ->
        listOf(FCC, SMPTE_240M).flatMap { matrix ->
          listOf(true, false).mapNotNull { full ->
            val nclx = CicpTag(1, 13, matrix, full)
            val landed = checkNotNull(AppleStillSamples.decoderMatrix(nclx, unsubsampled = false, still.bitDepth))
            val expected = "degraded: ${listOf(unreadMatrix(landed).message)}"
            val resolved = describe(overlayResolution(still.bytes(nclx)))
            if (resolved == expected) null else "$still with nclx $nclx resolved $resolved, where it is $expected"
          }
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun `a twelve bit 4 4 4 still under the identity matrix is refused past the bound`() {
    for (full in listOf(true, false)) {
      val nclx = CicpTag(1, 13, IDENTITY, full)
      val nearest = checkNotNull(ycbcrReading(IDENTITY, 1, 13).nearestReadable(IMAGE_IO_MATRICES - IDENTITY))

      val route = imageIoRouteOf(HeicIdentityPictures.twelveBitBytes(nclx))

      withClue("the identity matrix lands within the bound through ${nearest.matrix}") {
        nearest.withinBound shouldBe false
      }
      val refused = withClue("nclx $nclx landed") { route.shouldBeInstanceOf<PictureRoute.Refused>() }
      refused.message shouldBe pastBound(nearest)
      refused.message shouldBe refusalOf(nclx, unsubsampled = true, TWELVE_BITS)
    }
  }

  @Test
  fun `an eight and ten bit 4 4 4 still under the identity matrix at full range is decoded as it is`() {
    val nclx = CicpTag(1, 13, IDENTITY, true)
    for (bytes in listOf(HeicIdentityPictures.bytes(nclx), HeicIdentityPictures.tenBitBytes(nclx))) {
      val route = imageIoRouteOf(bytes).shouldBeInstanceOf<PictureRoute.Decoded>()

      route.limits shouldBe emptyList()
      pictureYcbcrCoding(route.bytes)?.matrix shouldBe IDENTITY
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
      val route = imageIoRouteOf(bytes + MOOV)

      withClue("$name with a moov box landed") {
        route.shouldBeInstanceOf<PictureRoute.Refused>()
      }.message shouldBe UNRESTATED
      withClue("$name without a moov box was refused") {
        (imageIoRouteOf(bytes) !is PictureRoute.Refused) shouldBe true
      }
    }
  }

  @Test
  fun `a tagged still whose irot and imir drop is refused is refused with core's sentence on both routes`() {
    val explicit = tagged(CicpTag(1, 13, BT709, true))
    val restated = tagged(CicpTag(1, 13, CHROMATICITY_DERIVED, true))
    val message = heifWithoutOrientation(explicit).shouldBeInstanceOf<UnturnedHeif.Refused>().message

    explicitRoute(explicit).shouldBeInstanceOf<PictureRoute.Refused>().message shouldBe message
    imageIoRouteOf(explicit).shouldBeInstanceOf<PictureRoute.Refused>().message shouldBe message
    imageIoRouteOf(restated).shouldBeInstanceOf<PictureRoute.Refused>().message shouldBe message
  }

  // An eight-bit 4:2:0 still with nclx, turned a quarter by an irot, and a moov box core refuses to drop it past.
  private fun tagged(nclx: CicpTag): ByteArray =
    OrientedPictures.taggedHeif(HeicSubsampledPictures.EIGHT_BIT_420.bytes(nclx), quarterTurns = 1, mirrorAxis = null) +
      MOOV

  private fun subsampledMiss(
    still: HeicSubsampledPictures.Still,
    nclx: CicpTag,
  ): String? {
    val route = imageIoRouteOf(still.bytes(nclx))
    val landed = AppleStillSamples.decoderMatrix(nclx, unsubsampled = false, still.bitDepth)
    val name = "$still with nclx $nclx"
    return when {
      landed == null -> {
        val refusal = refusalOf(nclx, unsubsampled = false, still.bitDepth)
        val message = (route as? PictureRoute.Refused)?.message
        if (message == refusal) null else "$name came to $route, where it is refused: $refusal"
      }
      route !is PictureRoute.Decoded -> {
        "$name came to $route, where it is decoded through matrix ${landed.matrix}"
      }
      landed.exact -> {
        if (route.limits.isEmpty()) null else "$name was degraded, where matrix ${landed.matrix} reads it exactly"
      }
      else -> {
        val matrix = pictureYcbcrCoding(route.bytes)?.matrix
        val limits = route.limits.map { it.message }
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
    const val BT709 = 1
    const val FCC = 4
    const val SMPTE_240M = 7
    const val CHROMATICITY_DERIVED = 12
    const val TWELVE_BITS = 12

    // An empty moov box, which the HEIF rewrite does not understand, so it restates nothing.
    val MOOV = byteArrayOf(0, 0, 0, 8) + "moov".encodeToByteArray()
  }
}
