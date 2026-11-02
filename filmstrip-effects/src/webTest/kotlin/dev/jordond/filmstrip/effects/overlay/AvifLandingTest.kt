package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.heifWithoutOrientation
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.OrientedPictures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Holds [avifLanding] to refusing a tagged AVIF whose `irot` and `imir` drop core refuses, with core's own sentence,
 * on the route that hands it over as it states its colour and on the one that restates it.
 */
@OptIn(InternalFilmstripApi::class)
class AvifLandingTest {
  @Test
  fun aTaggedAvifWhoseDropIsRefusedIsRefusedWithCoresSentenceOnBothRoutes() {
    val routes =
      listOf(
        "the explicit route" to MatrixAvif.EIGHT_BIT_420.bytes(CicpTag(1, 13, BT709, true)),
        "the restated route" to MatrixAvif.EIGHT_BIT_420.bytes(CicpTag(1, 13, CHROMATICITY_DERIVED, true)),
      )
    for ((route, still) in routes) {
      val bytes = OrientedPictures.taggedHeif(still, quarterTurns = 1, mirrorAxis = null) + MOOV
      val message = assertIs<UnturnedHeif.Refused>(heifWithoutOrientation(bytes)).message

      val landing = avifLanding(bytes)

      assertEquals(message, assertIs<AvifLanding.Refused>(landing, "$route landed").message, route)
    }
  }

  private companion object {
    const val BT709 = 1
    const val CHROMATICITY_DERIVED = 12

    // An empty moov box, past which core refuses to drop a tagged file's irot and imir.
    val MOOV = byteArrayOf(0, 0, 0, 8) + "moov".encodeToByteArray()
  }
}
