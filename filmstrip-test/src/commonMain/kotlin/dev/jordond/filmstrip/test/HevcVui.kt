package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * What a HEIC still's sequence parameter set signals in its video usability information, which a decoder may read
 * instead of the `colr nclx` box.
 *
 * @property videoSignalType Whether the video signal type is present at all. Without it the range reads as limited.
 * @property fullRange The video full range flag, false when [videoSignalType] is absent.
 * @property colour The colour description's primaries, transfer and matrix with [fullRange], or null when it has none.
 */
@InternalFilmstripApi
public enum class HevcVui(
  public val videoSignalType: Boolean,
  public val fullRange: Boolean,
  public val colour: CicpTag?,
  internal val configuration: String,
) {
  /**
   * No video signal type, so no range flag and no colour description.
   */
  Unsignalled(
    videoSignalType = false,
    fullRange = false,
    colour = null,
    configuration =
      HVCC_HEADER + HVCC_VPS +
        "a10001002542010103700000030090000003000003001ea020810596ea4932b80400000fa000000fa020" + HVCC_PPS,
  ),

  /**
   * The video full range flag set, with no colour description.
   */
  FullRange(
    videoSignalType = true,
    fullRange = true,
    colour = null,
    configuration =
      HVCC_HEADER + HVCC_VPS +
        "a10001002642010103700000030090000003000003001ea020810596ea4932b9b02000007d000003007d01" + HVCC_PPS,
  ),

  /**
   * The video full range flag set, with a colour description of BT.709 primaries, the sRGB transfer 13 and the BT.709
   * matrix 1.
   */
  FullRangeBt709(
    videoSignalType = true,
    fullRange = true,
    colour = CicpTag(1, 13, 1, true),
    configuration =
      HVCC_HEADER + HVCC_VPS +
        "a10001002942010103700000030090000003000003001ea020810596ea4932b9b80868082000007d000003007d01" + HVCC_PPS,
  ),

  /**
   * The video full range flag clear, with a colour description of BT.709 primaries, the sRGB transfer 13 and the BT.709
   * matrix 1.
   */
  LimitedRangeBt709(
    videoSignalType = true,
    fullRange = false,
    colour = CicpTag(1, 13, 1, false),
    configuration =
      HVCC_HEADER + HVCC_VPS +
        "a10001002942010103700000030090000003000003001ea020810596ea4932b9a80868082000007d000003007d01" + HVCC_PPS,
  ),

  /**
   * The video full range flag set, with a colour description of Display P3 primaries 12, the sRGB transfer 13 and the
   * BT.709 matrix 1.
   *
   * Made as [FullRangeBt709] is, with `--colorprim smpte432` in place of `--colorprim bt709`.
   */
  FullRangeDisplayP3(
    videoSignalType = true,
    fullRange = true,
    colour = CicpTag(12, 13, 1, true),
    configuration =
      HVCC_HEADER + HVCC_VPS +
        "a10001002942010103700000030090000003000003001ea020810596ea4932b9b86068082000007d000003007d01" + HVCC_PPS,
  ),
}

// The hvcC fields ahead of its arrays, Main Still Picture at level 3, and the video and picture parameter set arrays
// every HEIC still shares.
private const val HVCC_HEADER = "0103700000009000000000001ef000fcfdf8f800000f03"
private const val HVCC_VPS = "a00001001840010c01ffff03700000030090000003000003001eba0240"
private const val HVCC_PPS = "a2000100064401c1718112"
