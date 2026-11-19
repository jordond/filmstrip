package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.fullChromaFormat
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * Lays [read] over a frame of the fill's color and returns the opaque pad that leaves, in [pixelFormat].
 *
 * Every pixel no clip painted is still transparent here, so the fill shows through exactly there.
 * The fill source runs forever, so the overlay ends with the frames laid over it.
 */
internal fun FilterGraphBuilder.flattenOntoFill(
  read: String,
  negotiated: ResolvedComposition,
  frameRate: Int,
  pixelFormat: String,
): String {
  val background = "vbg"
  chain(emptyList(), fillSourceNodes(negotiated, frameRate), background)

  val flattened = "vflat"
  chain(
    listOf(background, read),
    listOf(
      // Subsampled, since the format node right after it writes the export's 4:2:0 format.
      overlayNode(pixelFormat, ChromaGrid.Subsampled, "shortest" to "1"),
      FilterNode(
        "format",
        "pix_fmts" to pixelFormat,
      ),
    ),
    flattened,
  )
  return flattened
}

/**
 * Lays [read] over opaque black of its own size and returns the pad that leaves, in [pixelFormat] or its 4:4:4 form so
 * the frame stays on [grid]. Names its pads from [label].
 *
 * `lut` writes the black from the frame itself, so nothing here has to know the size a clip's effects left it at.
 */
internal fun FilterGraphBuilder.overBlack(
  read: String,
  label: String,
  pixelFormat: String,
  grid: ChromaGrid,
): String {
  val base = "${label}blackIn"
  val top = "${label}top"
  split(read, listOf(base, top))
  val black = "${label}black"
  val opaqueBlack = FilterNode("lut", "y" to "16", "u" to "128", "v" to "128", "a" to "255")
  chain(listOf(base), listOf(opaqueBlack), black)
  val format = if (grid == ChromaGrid.Full) fullChromaFormat(pixelFormat) else pixelFormat
  val over = "${label}over"
  chain(listOf(black, top), listOf(overlayNode(pixelFormat, grid), FilterNode("format", "pix_fmts" to format)), over)
  return over
}

/**
 * Restates the grade on [transfer] onto a pad named [label] after [read], or hands [read] back when there is none.
 *
 * The effect chain can carry a frame through plane lanes, and the `mergeplanes` that puts the lanes back together
 * writes a frame with no primaries or transfer. The grade is set on it again so the encoder writes the file under it.
 */
internal fun FilterGraphBuilder.restateGrade(
  read: String,
  label: String,
  transfer: HdrTransfer?,
): String {
  val nodes = gradeNodes(transfer)
  if (nodes.isEmpty()) return read

  chain(listOf(read), nodes, label)
  return label
}

/**
 * Restates the HDR grade on [transfer] for a stage that drops it, or nothing when there is no grade.
 *
 * `color` and `lutyuv` name no color attributes of their own, so a fill they paint comes out with no matrix. A
 * conversion after it reads those codes through its own default rather than BT.2020, and the codes it writes are not
 * the ones the fill was painted in.
 */
internal fun gradeNodes(transfer: HdrTransfer?): List<FilterNode> {
  if (transfer == null) return emptyList()

  return listOf(
    FilterNode(
      "setparams",
      "color_primaries" to HDR_PRIMARIES,
      "color_trc" to transfer.ffmpegTag,
      "colorspace" to HDR_MATRIX,
    ),
  )
}

/**
 * Where the packed sRGB [argb] lands on a kept HDR grade on [transfer], as the ten-bit video range luma, Cb and Cr a
 * BT.2020 frame stores.
 *
 * The color is taken to the shared HDR light, then carried onto the grade's signal. A color option is eight-bit
 * sRGB and the filter drawing it runs its own matrix, so these codes are written into the frame directly instead.
 */
internal fun hdrPaintCodes(
  argb: Int,
  transfer: HdrTransfer,
): IntArray = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(argb)))

/**
 * The nodes that repaint every pixel of a frame in [argb] on a kept HDR grade on [transfer], ending on the grade.
 *
 * The frame goes to ten-bit planar and a table maps every input code to the ones [hdrPaintCodes] gives. `lutyuv` has
 * no ten-bit format with alpha, so a translucent [argb] is painted at sixteen bits instead, the ten-bit codes moved up
 * by six bits and the alpha scaled onto sixteen. Taking that frame back to ten bits drops the six bits again.
 */
internal fun hdrPaintNodes(
  argb: Int,
  transfer: HdrTransfer,
): List<FilterNode> {
  val (luma, cb, cr) = hdrPaintCodes(argb, transfer)
  val alpha = argb ushr ALPHA_SHIFT
  val paint =
    if (alpha == OPAQUE_ALPHA) {
      listOf(
        FilterNode("format", "pix_fmts" to HDR_PAINT_FORMAT),
        FilterNode("lutyuv", "y" to "$luma", "u" to "$cb", "v" to "$cr"),
      )
    } else {
      listOf(
        FilterNode("format", "pix_fmts" to HDR_PAINT_ALPHA_FORMAT),
        FilterNode(
          "lutyuv",
          "y" to "${luma shl SIXTEEN_BIT_SHIFT}",
          "u" to "${cb shl SIXTEEN_BIT_SHIFT}",
          "v" to "${cr shl SIXTEEN_BIT_SHIFT}",
          "a" to "${alpha * EIGHT_TO_SIXTEEN_BIT}",
        ),
      )
    }
  return paint + gradeNodes(transfer)
}

/**
 * A source of frames painted flat in the fill, carrying the grade.
 *
 * On a kept grade one frame is repainted by [hdrPaintNodes] and `loop` then repeats that frame for as long as the
 * flatten reads it. Otherwise `color` paints the fill's own color.
 */
private fun fillSourceNodes(
  negotiated: ResolvedComposition,
  frameRate: Int,
): List<FilterNode> {
  val size = "${negotiated.output.size.width}x${negotiated.output.size.height}"
  val rate = frameRate.toString()
  val transfer =
    negotiated.hdrTransfer ?: return listOf(
      FilterNode("color", "c" to fillColor(negotiated.fill), "s" to size, "r" to rate),
    )

  return listOf(FilterNode("color", "s" to size, "r" to rate), FilterNode("trim", "end_frame" to "1")) +
    hdrPaintNodes(fillArgb(negotiated.fill), transfer) +
    FilterNode("loop", "loop" to "-1", "size" to "1")
}

// The planar formats a kept grade's paint is written in, the layouts the codes [hdrPaintCodes] returns are laid out
// for. Whatever reads the frame next converts it to its own format, which only repacks the same codes.
private const val HDR_PAINT_FORMAT = "yuv420p10le"
private const val HDR_PAINT_ALPHA_FORMAT = "yuva420p16le"
private const val SIXTEEN_BIT_SHIFT = 6
private const val EIGHT_TO_SIXTEEN_BIT = 257
private const val ALPHA_SHIFT = 24
private const val OPAQUE_ALPHA = 0xFF
