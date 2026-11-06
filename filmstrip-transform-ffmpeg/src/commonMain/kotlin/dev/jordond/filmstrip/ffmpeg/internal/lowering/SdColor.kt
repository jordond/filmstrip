package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip

/**
 * The filter that moves an SD clip onto BT.709's primaries, which [SdColor.toBt709Nodes] lowers to.
 */
internal const val SD_COLOR_FILTER: String = "colorspace"

/**
 * The colour an SDR clip on SD primaries decodes with, read off its own tags.
 *
 * @property primaries The color primaries code point, one of core's SD primaries.
 * @property matrix How ffmpeg spells the clip's matrix.
 */
internal data class SdColor(
  val primaries: Int,
  val matrix: String,
) {
  /**
   * The node writing this clip as true BT.709, matrix and primaries, the same colour `sdSourceDisplayed` reads.
   *
   * The input range stays off the frame, so a full range clip reads as one. Both curves are named as BT.709's, since
   * that is the curve the shared function goes through whatever a clip's transfer tag says. The filter works out its
   * own matrix from the same chromaticities core holds, and `FfmpegSdColorTest` holds the two together.
   */
  private val toBt709: FilterNode
    get() =
      FilterNode(
        SD_COLOR_FILTER,
        "ispace" to matrix,
        "iprimaries" to if (primaries == SMPTE_C_PRIMARIES) SMPTE_C_TAG else EBU_TAG,
        "itrc" to BT709_TAG,
        "all" to BT709_TAG,
        "range" to "tv",
        "format" to "yuv420p",
      )

  /**
   * The nodes writing a [frame] sized picture of this clip as true BT.709.
   *
   * The `colorspace` filter refuses an odd width or height, which is that filter's limit, so an odd picture is padded
   * up to even for it and cropped back after.
   */
  fun toBt709Nodes(frame: Size): List<FilterNode> =
    if (frame.width % 2 == 0 && frame.height % 2 == 0) {
      listOf(toBt709)
    } else {
      listOf(
        FilterNode("pad", "w" to "ceil(iw/2)*2", "h" to "ceil(ih/2)*2"),
        toBt709,
        FilterNode("crop", "w" to frame.width.toString(), "h" to frame.height.toString(), "x" to "0", "y" to "0"),
      )
    }
}

/**
 * Every filter an SD clip can put in a graph by name, an odd picture's padding and the tags a plan written BT.709 ends
 * on included.
 */
internal fun SdColor.filterNames(): Set<String> =
  FilterFragment(chain = toBt709Nodes(Size(1, 1)) + bt709Tags()).filterNames()

/**
 * The size this clip's frames reach the graph at, since ffmpeg turns a clip by its rotation as it decodes it.
 */
internal val ResolvedClip.decodedFrame: Size
  get() {
    val coded = info.video?.codedSize ?: Size(0, 0)
    return if (info.video?.rotationDegrees?.rem(QUARTER_TURNS_HALF) == 0) coded else Size(coded.height, coded.width)
  }

/**
 * The [SdColor] of a stream ffprobe reports with these tags, or null when the stream is not on SD primaries.
 *
 * An HDR transfer is left to the tone map, and a matrix the filter cannot read as Y'CbCr leaves the stream alone. An
 * untagged matrix on SD primaries reads as BT.601's, the matrix those primaries come with.
 */
internal fun sdColorOf(
  primaries: String?,
  matrix: String?,
  transfer: String?,
): SdColor? {
  val codePoint =
    when (primaries) {
      SMPTE_C_TAG -> SMPTE_C_PRIMARIES
      EBU_TAG -> EBU_PRIMARIES
      else -> return null
    }
  if (transfer in HDR_TRANSFER_TAGS) return null
  return when (matrix) {
    null, "unknown" -> SdColor(codePoint, SMPTE_C_TAG)
    in READABLE_MATRICES -> SdColor(codePoint, matrix)
    else -> null
  }
}

private const val QUARTER_TURNS_HALF = 180
private const val BT709_TAG = "bt709"
private const val SMPTE_C_TAG = "smpte170m"
private const val EBU_TAG = "bt470bg"

private val READABLE_MATRICES = setOf(BT709_TAG, SMPTE_C_TAG, EBU_TAG)
private val HDR_TRANSFER_TAGS = setOf("smpte2084", "arib-std-b67")
