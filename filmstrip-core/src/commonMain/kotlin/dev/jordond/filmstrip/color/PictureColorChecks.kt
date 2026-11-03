package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.color.icc.GreyProfile
import kotlin.math.abs

/**
 * Whether this description fits a picture storing [channels] color channels, or one whose count is unknown when
 * [channels] is null.
 *
 * An ICC profile fits only when it parses, and then only its own color space, three channels for RGB and one for
 * grey. CICP and PNG gamma describe a grey picture as well as a color one.
 */
internal fun PictureColor.describes(channels: Int?): Boolean =
  when (this) {
    is Icc -> parsed?.let { channels == null || channels == if (it is GreyProfile) 1 else 3 } == true
    is Cicp, is PngGamma -> channels == null || channels == 1 || channels == 3
  }

/**
 * Whether this description is sRGB itself, the CICP code points for sRGB or PNG gamma at PNG-3's sRGB equivalence, so
 * the picture lands exactly as an untagged one does.
 *
 * An ICC profile is never sRGB here, since only its parsed curves and colorants can say so.
 */
internal val PictureColor.isSrgb: Boolean
  get() =
    when (this) {
      is Icc -> {
        false
      }
      is Cicp -> {
        isSupported && primaries == SRGB_PRIMARIES && transfer == SRGB_TRANSFER
      }
      is PngGamma -> {
        (gamma == null || abs(gamma - SRGB_PNG_GAMMA) <= 1) &&
          (chromaticities == null || chromaticities == REC709_CHROMATICITIES)
      }
    }

private const val SRGB_PNG_GAMMA = 45455
