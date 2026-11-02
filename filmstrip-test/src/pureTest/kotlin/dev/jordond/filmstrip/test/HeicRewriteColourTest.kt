package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.pictureSrgbCodes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads the colour description core finds in every test HEIC, alone and as a grid, before and after its codestream is
 * rewritten to state its `nclx`, and compares the sRGB codes each draws a set of probe colours at.
 *
 * Every HEIC with an `nclx` is also read with Display P3's primaries 12 in place of its own, so the rewrite is held on
 * descriptions that move a colour as well as ones that equal sRGB.
 */
class HeicRewriteColourTest {
  private val pictures =
    (ColourPictures.HEICS_WITH_SIGNAL_VARIANTS + ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA)
      .flatMap { picture -> listOfNotNull(picture, picture.inDisplayP3()) }

  private val files =
    pictures.flatMap { picture -> listOf("$picture" to picture.bytes(), "$picture grid" to picture.gridBytes()) }

  @Test
  fun rewritingAHeicKeepsTheColourItDescribes() {
    for ((name, original) in files) {
      val rewritten = heifWithExplicitCodestreamColour(original).handed

      assertContentEquals(
        pictureSrgbCodes(pictureColour(original), PROBES).toList(),
        pictureSrgbCodes(pictureColour(rewritten), PROBES).toList(),
        "$name draws another colour once rewritten",
      )
    }
  }

  @Test
  fun theRewrittenHeicsIncludeDescriptionsThatMoveAColour() {
    val rewritten = files.filter { (_, bytes) -> !heifWithExplicitCodestreamColour(bytes).handed.contentEquals(bytes) }
    assertFalse(rewritten.isEmpty(), "no HEIC was rewritten")

    val untagged = pictureSrgbCodes(null, PROBES).toList()
    assertTrue(
      rewritten.any { (_, bytes) -> pictureSrgbCodes(pictureColour(bytes), PROBES).toList() != untagged },
      "every rewritten HEIC draws the probes as untagged",
    )
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

    // Colours a change of primaries or curve moves, with a mid grey and a dark one.
    val PROBES =
      intArrayOf(
        ColourPictures.TEXEL,
        0xFFFF0000.toInt(),
        0xFF00FF00.toInt(),
        AuthoredColours.MID_GREY,
        AuthoredColours.MID_GREEN,
        0xFF101820.toInt(),
      )
  }
}
