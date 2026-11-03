package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithoutOrientation
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Holds [avifRouteOf] to refusing a tagged AVIF whose `irot` and `imir` drop core refuses, with core's own sentence,
 * on the route that hands it over as it states its color and on the one that restates it.
 */
class AvifRouteTest {
  @Test
  fun aTaggedAvifWhoseDropIsRefusedIsRefusedWithCoresSentenceOnBothRoutes() {
    val routes =
      listOf(
        "the explicit route" to MatrixAvif.EIGHT_BIT_420.bytes(CicpTag(1, 13, BT709, true)),
        "the restated route" to MatrixAvif.EIGHT_BIT_420.bytes(CicpTag(1, 13, CHROMATICITY_DERIVED, true)),
      )
    for ((route, still) in routes) {
      val bytes = OrientedPictures.taggedHeif(still, quarterTurns = 1, mirrorAxis = null) + MOOV
      val message = heifWithoutOrientation(bytes).shouldBeInstanceOf<UnturnedHeif.Refused>().message

      val decoded = avifRouteOf(bytes)

      withClue(route) {
        withClue("$route landed") { decoded.shouldBeInstanceOf<PictureRoute.Refused>() }.message shouldBe message
      }
    }
  }

  private companion object {
    const val BT709 = 1
    const val CHROMATICITY_DERIVED = 12

    // An empty moov box, past which core refuses to drop a tagged file's irot and imir.
    val MOOV = byteArrayOf(0, 0, 0, 8) + "moov".encodeToByteArray()
  }
}
