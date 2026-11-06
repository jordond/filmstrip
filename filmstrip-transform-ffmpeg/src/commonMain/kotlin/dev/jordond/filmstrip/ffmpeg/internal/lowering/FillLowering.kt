package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Fill
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
    listOf(overlayNode("shortest" to "1"), FilterNode("format", "pix_fmts" to pixelFormat)),
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
  chain(listOf(black, top), listOf(overlayNode(), FilterNode("format", "pix_fmts" to format)), over)
  return over
}

/**
 * Restates the grade on [transfer] onto a pad named [label] after [read], or hands [read] back when there is none.
 *
 * The effect chain is where a frame can go through RGB and come back, and what comes back is
 * untagged. ffmpeg 7 and newer reads the grade off the frames either side of it, but below that
 * the conversion out of RGB falls back to BT.601 and the picture is written through the wrong
 * matrix.
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
 * `color` names no color attributes of its own, and a frame that has been through RGB carries
 * none either. ffmpeg 7 and newer take them from the neighbouring input, so restating costs
 * nothing there, but below that the frame comes out untagged and every conversion after it reads
 * a BT.2020 picture through the wrong matrix.
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
 * Where the fill's color lands on a kept HDR grade on [transfer], as the ten-bit video range luma, Cb and Cr a
 * BT.2020 frame stores.
 *
 * The color is taken to the shared HDR light, then carried onto the grade's signal. A color option is eight-bit
 * sRGB and the filter drawing it runs its own matrix, so these codes are written into the frame directly instead.
 */
internal fun hdrFillCodes(
  fill: Fill,
  transfer: HdrTransfer,
): IntArray = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(fillArgb(fill))))

/**
 * A source of frames painted flat in the fill, carrying the grade.
 *
 * On a kept grade one frame is written as the ten-bit codes [hdrFillCodes] puts the fill at, through a table that
 * maps every input code to that one, and `loop` then repeats that frame for as long as the flatten reads it.
 * Otherwise `color` paints the fill's own color.
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

  val (luma, cb, cr) = hdrFillCodes(negotiated.fill, transfer)
  return listOf(
    FilterNode("color", "s" to size, "r" to rate),
    FilterNode("trim", "end_frame" to "1"),
    FilterNode("format", "pix_fmts" to HDR_FILL_FORMAT),
    FilterNode("lutyuv", "y" to luma.toString(), "u" to cb.toString(), "v" to cr.toString()),
  ) + gradeNodes(transfer) + FilterNode("loop", "loop" to "-1", "size" to "1")
}

// The planar ten-bit format a kept grade's fill is written in, the layout the codes [hdrFillCodes] returns are laid
// out for. The flatten converts it to whatever the encoder takes, which only repacks the same codes.
private const val HDR_FILL_FORMAT = "yuv420p10le"
