package dev.jordond.filmstrip.test

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The raster representatives of [ColorPictures], checked for a still on every path a backend's raster tests read.
 *
 * Each path is a description of the still a backend must meet, and the test names every path no representative takes.
 */
class ColorPictureRepresentativesTest {
  @Test
  fun rasterRepresentativesCoverEveryStillPath() {
    val paths =
      mapOf<String, (HeifPicture) -> Boolean>(
        "an AVIF" to { it is AvifPicture },
        "a HEIC with a plain BT.709 sRGB nclx" to { it is HeicPicture && it.isPlainNclx() },
        "a still described only by its ICC profile" to { it.nclx == null && it.profile != null },
        "a HEIC whose VUI range disagrees with its nclx" to { it is HeicPicture && it.vuiRangeDisagrees() },
        "a HEIC whose nclx names primaries 12 beside the BT.709 matrix" to {
          it is HeicPicture && it.nclx?.let { nclx -> nclx.primaries == DISPLAY_P3 && nclx.matrix == BT709 } == true
        },
      )
    val missing = paths.filterValues { path -> ColorPictures.RASTER_REPRESENTATIVES.none(path) }.keys

    withClue("No raster representative is $missing") { missing.isEmpty() shouldBe true }
  }

  private fun HeicPicture.vuiRangeDisagrees(): Boolean {
    val nclx = nclx ?: return false
    return vui.videoSignalType && vui.fullRange != nclx.fullRange
  }

  private fun HeicPicture.isPlainNclx(): Boolean {
    val nclx = nclx ?: return false
    return profile == null && vui == HevcVui.Unsignalled && nclx.primaries == BT709 && nclx.matrix == BT709 &&
      nclx.transfer == SRGB_TRANSFER && nclx.fullRange
  }

  private companion object {
    const val BT709 = 1
    const val DISPLAY_P3 = 12
    const val SRGB_TRANSFER = 13
  }
}
