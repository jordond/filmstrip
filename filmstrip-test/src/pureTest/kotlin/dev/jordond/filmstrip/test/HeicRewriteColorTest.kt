package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.picture.pictureColor
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Reads the color description core finds in every test HEIC, alone and as a grid, before and after its codestream is
 * rewritten to state its `nclx`, and compares the sRGB codes each draws a set of probe colors at.
 *
 * Every HEIC with an `nclx` is also read with Display P3's primaries 12 in place of its own, so the rewrite is held on
 * descriptions that move a color as well as ones that equal sRGB.
 */
class HeicRewriteColorTest {
  private val pictures =
    (ColorPictures.HEICS_WITH_SIGNAL_VARIANTS + ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA)
      .flatMap { picture -> listOfNotNull(picture, picture.inDisplayP3()) }

  private val files =
    pictures.flatMap { picture -> listOf("$picture" to picture.bytes(), "$picture grid" to picture.gridBytes()) }

  @Test
  fun rewritingAHeicKeepsTheColorItDescribes() {
    for ((name, original) in files) {
      val rewritten = heifWithExplicitCodestreamColor(original).handed

      withClue("$name draws another color once rewritten") {
        pictureSrgbCodes(pictureColor(rewritten), PROBES).toList() shouldBe
          pictureSrgbCodes(pictureColor(original), PROBES).toList()
      }
    }
  }

  @Test
  fun theRewrittenHeicsIncludeDescriptionsThatMoveAColor() {
    val rewritten = files.filter { (_, bytes) -> !heifWithExplicitCodestreamColor(bytes).handed.contentEquals(bytes) }
    withClue("no HEIC was rewritten") { rewritten.isEmpty() shouldBe false }

    val untagged = pictureSrgbCodes(null, PROBES).toList()
    withClue("every rewritten HEIC draws the probes as untagged") {
      rewritten.any { (_, bytes) -> pictureSrgbCodes(pictureColor(bytes), PROBES).toList() != untagged } shouldBe true
    }
  }

  private fun HeicPicture.inDisplayP3(): HeicPicture? {
    val tag = nclx ?: return null
    return HeicPicture(
      "$name with Display P3 primaries",
      CicpTag(DISPLAY_P3_PRIMARIES, tag.transfer, tag.matrix, tag.fullRange),
      profile,
      ycbcr,
      vui,
      slice,
    )
  }

  private companion object {
    const val DISPLAY_P3_PRIMARIES = 12

    // Colors a change of primaries or curve moves, with a mid grey and a dark one.
    val PROBES =
      intArrayOf(
        ColorPictures.TEXEL,
        0xFFFF0000.toInt(),
        0xFF00FF00.toInt(),
        AuthoredColors.MID_GREY,
        AuthoredColors.MID_GREEN,
        0xFF101820.toInt(),
      )
  }
}
