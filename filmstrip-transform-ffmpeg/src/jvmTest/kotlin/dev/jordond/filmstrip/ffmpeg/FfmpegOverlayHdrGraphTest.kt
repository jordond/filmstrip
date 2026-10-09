package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.ffmpeg.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.HdrRender.FLOAT_RGB
import dev.jordond.filmstrip.ffmpeg.HdrRender.FRAME_YUV
import dev.jordond.filmstrip.ffmpeg.HdrRender.GRADE_YUV
import dev.jordond.filmstrip.ffmpeg.HdrRender.SIDE
import dev.jordond.filmstrip.ffmpeg.HdrRender.WIDE_YUV
import dev.jordond.filmstrip.ffmpeg.HdrRender.attributes
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.TEN_BIT_CHROMA_MID
import dev.jordond.filmstrip.media.TEN_BIT_LUMA_FLOOR
import dev.jordond.filmstrip.media.TEN_BIT_LUMA_RANGE
import dev.jordond.filmstrip.media.TEN_TO_SIXTEEN_BITS
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.nitsFromSignal
import dev.jordond.filmstrip.media.peakNits
import dev.jordond.filmstrip.media.pqSignalFromNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.signalFromTenBitCodes
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREY
import dev.jordond.filmstrip.test.HdrOverlayCells.WHITE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Both ends of the blend an overlay on a kept HDR grade runs, and the branches that bring the overlay's picture and
 * mask to it, driven by the ffmpeg on PATH with no encoder after them.
 *
 * The decode end takes the region from the grade's ten-bit Y'CbCr to display light and the encode end takes the blend's
 * light back to Y'CbCr. Each is fed flat frames and a dense grid of in-gamut signals, one per pixel, the encode end a
 * ramp around its halves as well, and the two run back to back over cells whose chroma steps. The picture branch is fed
 * every sixteen-bit sample on each channel and has to reach the light the shared transfer decodes it to, and the mask
 * branch has to carry each alpha onto every plane as it is. Every frame is read back with no encoder noise to hide a
 * matrix that drifts. Every expected code comes from [authoredHdrNits], [signalFromNits] and [tenBitCodesFromSignal],
 * and every expected signal from [signalFromTenBitCodes].
 */
class FfmpegOverlayHdrGraphTest {
  @Test
  fun `the encode end writes each colour's light at the codes the shared matrix puts it at`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        val post = fragmentOn(transfer).post
        COLOURS.mapNotNull { colour ->
          val light = authoredHdrNits(colour).map { it / transfer.peakNits }
          val source = "format=$FLOAT_RGB,geq=r=${light[0]}:g=${light[1]}:b=${light[2]}"
          val read = flatCodes(rendered(source, post, GRADE_YUV), SIDE)
          val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(colour)))
          miss(read, expected, "$transfer ${hex(colour)}")
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // Each cell moves all three of a colour's codes by the same amount, a little either side of a half. A second
  // rounding, or a plane pulling on another before the codes are worked out, tips a code over its half. How far one
  // plane pulls on another follows the gaps between them, so a saturated colour runs over many steps and each primary,
  // whose planes sit in another order, runs at the half step.
  @Test
  fun `the encode end lands each pixel of a ramp on the codes the shared matrix rounds it to`() {
    val cells =
      RAMP_STEPS.flatMap { nearHalves(SATURATED_SIGNAL, it) } + PRIMARY_SIGNALS.flatMap { nearHalves(it, HALF_STEP) }
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        val post = fragmentOn(transfer).post
        val nits = cells.map { transfer.nitsFromSignal(it.second) }
        val light = (0..2).map { channel -> ramp(nits.map { it[channel] / transfer.peakNits }) }
        val source = "format=$FLOAT_RGB,geq=r='${light[0]}':g='${light[1]}':b='${light[2]}'"
        val read = rampCodes(rendered(source, post, GRADE_YUV, cells.size * CELL, CELL_HEIGHT), cells.size)
        cells.indices.mapNotNull { cell ->
          val expected = tenBitCodesFromSignal(transfer.signalFromNits(nits[cell]))
          read[cell].firstNotNullOfOrNull { miss(it, expected, "$transfer ${cells[cell].first}") }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // swscale pulls the planes of the float to sixteen-bit step against each other, and the encode lands on the right
  // codes only while that pull stays under half a ten-bit code. A dense grid of in-gamut signals, the cube's corners
  // and edges among them, pins it at every pixel. The grade's own 4:2:0 step is left off so each pixel keeps its own
  // chroma, and the sixteen-bit samples before it are rounded to the nearest ten-bit code here. The ramp test above
  // runs that real step. A code may only differ from the shared matrix's where it sits across a tie, since the graph
  // works it out in double from float light and the shared matrix in float.
  @Test
  fun `the encode end puts every pixel of an in-gamut grid on the shared matrix's codes or across a tie from them`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        val whole = fragmentOn(transfer).post
        assertEquals(FilterNode("format", "pix_fmts" to GRADE_YUV), whole.last(), "$transfer: the encode ends on 4:2:0")
        val nits = GRID.map { transfer.nitsFromSignal(it) }
        val light = planarFloats(listOf(1, 2, 0).map { channel -> nits.map { it[channel] / transfer.peakNits } })
        val read = gridSamples(RawFrame(light, FLOAT_RGB).rendered(whole.dropLast(1), WIDE_YUV), GRID.size)
        GRID.indices.mapNotNull { pixel ->
          val codes = IntArray(3) { (read[it][pixel] + TEN_TO_SIXTEEN_BITS / 2) / TEN_TO_SIXTEEN_BITS }
          val signal = transfer.signalFromNits(nits[pixel])
          val expected = tenBitCodesFromSignal(signal)
          if (codes.contentEquals(expected) || acrossTies(codes, signal)) {
            null
          } else {
            miss(codes, expected, "$transfer ${GRID[pixel].toList()}")
          }
        }
      }

    assertTrue(misses.isEmpty(), reported(misses))
  }

  // The frame is tagged full range over video range codes, which only come back out of the encode as they went in if
  // the chain's limited range reaches it. Chroma steps between the cells, so a plane squeezed or pulled on the way
  // shows against its neighbours.
  @Test
  fun `a frame tagged full range comes back through both ends on the codes it holds`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        val fragment = fragmentOn(transfer)
        val graph = fragment.chain + fragment.post
        val codes = COLOURS.map { tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(it))) }
        val planes = (0..2).map { plane -> ramp(codes.map { it[plane].toFloat() }) }
        val drawn = "geq=lum='${planes[0]}':cb='${planes[1]}':cr='${planes[2]}'"
        val source = "format=yuv444p10le,$drawn,format=$GRADE_YUV,setrange=full"
        val read = rampCodes(rendered(source, graph, GRADE_YUV, COLOURS.size * CELL, CELL_HEIGHT), COLOURS.size)
        COLOURS.indices.mapNotNull { cell ->
          read[cell].firstNotNullOfOrNull { miss(it, codes[cell], "$transfer ${hex(COLOURS[cell])}") }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `the decode end reads each colour's codes as the light the shared matrix decodes them to`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        val chain = fragmentOn(transfer).chain
        COLOURS.mapNotNull { colour ->
          val codes = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(colour)))
          val source = "format=yuv444p10le,geq=lum=${codes[0]}:cb=${codes[1]}:cr=${codes[2]},format=$GRADE_YUV"
          decodeMiss(transfer, flatLight(rendered(source, chain, FLOAT_RGB), SIDE), codes, hex(colour))
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `the decode end reads every code of an in-gamut grid as the light the shared matrix decodes it to`() {
    val misses = gridDecodeMisses(tag = null)

    assertTrue(misses.isEmpty(), reported(misses))
  }

  // A full range tag on video range codes would have them squeezed a second time before the matrix reads them.
  @Test
  fun `the decode end reads a frame tagged full range as the video range codes it holds`() {
    val misses = gridDecodeMisses(tag = FilterNode("setrange", "range" to "full"))

    assertTrue(misses.isEmpty(), reported(misses))
  }

  // The shared transfer puts PQ's black at a signal just above zero, so black light read back lands that far off the
  // codes it decodes from, and the grid's PQ black pixels read exactly that. The decode bound has to hold it with the
  // float rounding on top, and sits under twice it so the bound stays as tight as what it covers.
  @Test
  fun `the decode bound holds PQ's black and stays under twice it`() {
    val black = pqSignalFromNits(0f) * TEN_BIT_LUMA_RANGE

    assertTrue(black <= DECODE_BOUND, "PQ's black reads $black codes off, over the decode bound of $DECODE_BOUND")
    assertTrue(DECODE_BOUND < black * 2, "the decode bound of $DECODE_BOUND is twice PQ's black of $black or more")
  }

  // The codes of every signal on the grid, drawn at 4:4:4 so each pixel keeps its own chroma, through the decode end.
  private fun gridDecodeMisses(tag: FilterNode?): List<String> =
    HdrTransfer.entries.flatMap { transfer ->
      val codes = GRID.map { tenBitCodesFromSignal(transfer.signalFromNits(transfer.nitsFromSignal(it))) }
      val planes = (0..2).map { plane -> IntArray(codes.size) { codes[it][plane] } }
      val drawn = RawFrame(planarShorts(planes), FRAME_YUV, tag)
      val light = gridLight(drawn.rendered(fragmentOn(transfer).chain, FLOAT_RGB), GRID.size)
      GRID.indices.mapNotNull { pixel -> decodeMiss(transfer, light[pixel], codes[pixel], "${GRID[pixel].toList()}") }
    }

  // The picture arrives as sixteen-bit samples on the grade's signal. Each plane walks every sample in an order of its
  // own, so a pixel's planes sit all over the range against each other, and every pixel has to reach the light the
  // shared transfer decodes its samples to. The branch is run from where it leaves sixteen bits.
  @Test
  fun `the picture branch reads every sixteen-bit sample as the light the shared transfer decodes it to`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        val chain = fragmentOn(transfer).auxInputs[0].chain
        val decode = chain.dropWhile { it.name != PlaneLanes.NAME }
        assertTrue(decode.isNotEmpty(), "$transfer: the picture goes to float on plane lanes, read ${chain.size} nodes")
        val light = gridLight(RawFrame(packedShorts(PICTURE), PICTURE_RGBA).rendered(decode, FLOAT_RGB), HdrGrid.PIXELS)
        light.indices.mapNotNull { pixel ->
          val read = transfer.signalFromNits(FloatArray(3) { light[pixel][it] * transfer.peakNits })
          val off = read.indices.maxOf { abs(read[it] - PICTURE[it][pixel].toFloat() / SIXTEEN_BIT_MAX) }
          val codesOff = off * TEN_BIT_LUMA_RANGE
          if (codesOff <= DECODE_BOUND) {
            null
          } else {
            "$transfer pixel $pixel read ${read.toList()} from ${PICTURE.map { it[pixel] }}, $codesOff codes off"
          }
        }
      }

    assertTrue(misses.isEmpty(), reported(misses))
  }

  // maskedmerge reads the alpha off every plane of the mask, so each plane has to carry the alpha's sample as it is.
  // The branch is run from the last colorchannelmixer, the one that puts its alpha on every colour.
  @Test
  fun `the mask branch carries every sixteen-bit alpha onto each plane as it is`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        val chain = fragmentOn(transfer).auxInputs[1].chain
        assertEquals(PlaneLanes.NAME, chain.last().name, "$transfer: the mask goes to float on plane lanes")
        val mixer = chain.indexOfLast { it.name == "colorchannelmixer" }
        assertTrue(mixer >= 0, "$transfer: the mask puts its alpha on every colour, read ${chain.size} nodes")
        val decode = chain.drop(mixer)
        val bytes = RawFrame(packedShorts(PICTURE), PICTURE_RGBA).rendered(decode, FLOAT_RGB)
        val read = gridLight(bytes, HdrGrid.PIXELS)
        read.indices.mapNotNull { pixel ->
          val alpha = PICTURE[3][pixel]
          val planes = read[pixel].map { (it * SIXTEEN_BIT_MAX).roundToInt() }
          if (planes.all { it == alpha }) null else "$transfer pixel $pixel read $planes, expected $alpha"
        }
      }

    assertTrue(misses.isEmpty(), reported(misses))
  }

  private fun reported(misses: List<String>): String =
    "${misses.size} pixels off\n" + misses.take(HdrGrid.MISSES_SHOWN).joinToString("\n")

  // Whether every plane of [codes] is a code the shared matrix puts [signal] at, or puts it at once the signal moves
  // ENCODE_TIE of a code down or up on every plane, to within the float the signal is held in. A plane moved that way
  // only changes code where it sits that close to a half.
  private fun acrossTies(
    codes: IntArray,
    signal: FloatArray,
  ): Boolean {
    val near = listOf(-ENCODE_TIE, ENCODE_TIE).map { by -> FloatArray(3) { signal[it] + by * ONE_CODE[it] } }
    val tied = near.map { tenBitCodesFromSignal(it) } + listOf(tenBitCodesFromSignal(signal))
    return codes.indices.all { plane -> tied.any { it[plane] == codes[plane] } }
  }

  // The light read back is put on the signal through the shared transfer. It has to land on the signal the shared
  // matrix decodes the codes to, short only of DECODE_BOUND.
  private fun decodeMiss(
    transfer: HdrTransfer,
    light: FloatArray,
    codes: IntArray,
    what: String,
  ): String? {
    val read = transfer.signalFromNits(FloatArray(3) { light[it] * transfer.peakNits })
    val expected = signalFromTenBitCodes(codes[0], codes[1], codes[2])
    val off = read.indices.maxOf { abs(read[it] - expected[it]) } * TEN_BIT_LUMA_RANGE
    return if (off <= DECODE_BOUND) {
      null
    } else {
      "$transfer $what read ${read.toList()}, expected ${expected.toList()} from ${codes.toList()}, $off codes off"
    }
  }

  private fun miss(
    read: IntArray,
    expected: IntArray,
    what: String,
  ): String? = if (read.contentEquals(expected)) null else "$what read ${read.toList()}, expected ${expected.toList()}"

  // The signal [step] codes from [signal]'s own codes on all three planes at once, just under and just over it, as the
  // shared matrix decodes it.
  private fun nearHalves(
    signal: FloatArray,
    step: Float,
  ): List<Pair<String, FloatArray>> {
    val codes = tenBitCodesFromSignal(signal)
    val whole = signalFromTenBitCodes(codes[0], codes[1], codes[2])
    val perCode = signalFromTenBitCodes(codes[0] + 1, codes[1] + 1, codes[2] + 1)
    return listOf(step - NEAR_HALF, step + NEAR_HALF).map { at ->
      "${codes.toList()} step $at" to FloatArray(3) { whole[it] + at * (perCode[it] - whole[it]) }
    }
  }

  // One value per cell, each cell CELL pixels wide, as a geq expression on X.
  private fun ramp(values: List<Float>): String =
    values.dropLast(1).withIndex().joinToString("") { (cell, value) -> "if(lt(X,${(cell + 1) * CELL}),$value," } +
      values.last() + ")".repeat(values.size - 1)

  private fun fragmentOn(transfer: HdrTransfer): FilterFragment {
    val overlay = ImageOverlay(ImageSource.ofBytes(pngOf(WHITE, OVERLAY_SIZE)), Corner.TopStart)
    val resolution = BuiltInEffectResolver().resolve(overlay, CAPABILITIES, attributes(transfer, 1.seconds))
    val resolved = assertIs<EffectResolution.Resolved>(resolution, "$transfer: an opaque overlay resolves")
    return resolved.effect.fragment.written()
  }

  // One flat frame of [source] run through [nodes] and written raw as [format], with nothing converting it on the way
  // out. The nodes go through the graph builder, as the backend writes them.
  private fun rendered(
    source: String,
    nodes: List<FilterNode>,
    format: String,
    width: Int = SIDE,
    height: Int = SIDE,
  ): ByteArray = filtered(emptyList(), "color=c=black:s=${width}x$height:r=1,$source", nodes, format)

  // The luma, Cb and Cr at every pixel of each cell of a ten-bit 4:2:0 ramp one cell high, one array per pixel. Chroma
  // is read off the middle half of the cell, since 4:2:0 resampling blends neighbouring cells' chroma at its edges.
  private fun rampCodes(
    bytes: ByteArray,
    cells: Int,
  ): List<List<IntArray>> {
    val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val width = cells * CELL
    val luma = width * CELL_HEIGHT
    val chromaWidth = width / 2
    assertEquals(luma + luma / 2, samples.remaining(), "a ten-bit 4:2:0 ramp of $cells cells")

    fun at(index: Int): Int = samples.get(index).toInt() and 0xFFFF

    return (0 until cells).map { cell ->
      (0 until CELL * CELL_HEIGHT).map { pixel ->
        val x = cell * CELL + pixel % CELL
        val y = pixel / CELL
        val middle = cell * CELL + CELL / 4 + pixel % (CELL / 2)
        val chroma = y / 2 * chromaWidth + middle / 2
        intArrayOf(at(y * width + x), at(luma + chroma), at(luma + luma / 4 + chroma))
      }
    }
  }

  private fun hex(colour: Int): String = colour.toUInt().toString(16)

  private companion object {
    // The opaque overlays of the shared grid: white, where a gain error is widest, and two colours in the middle of
    // the range.
    val COLOURS = listOf(WHITE, MID_GREY, MID_GREEN)

    // A saturated colour's signal, whose channels sit far apart so one pulling on another shows, and the steps from
    // its whole codes the ramp reads either side of. Its luma sits above its Cr, which sits above its Cb.
    val SATURATED_SIGNAL = floatArrayOf(0.25f, 0.7f, 0.15f)
    const val HALF_STEP = 0.5f
    val RAMP_STEPS = listOf(-40.5f, -12.5f, -3.5f, HALF_STEP, 7.5f, 23.5f, 61.5f)

    // A red, a green and a blue clear of the gamut's edges, so a half step does not clip. Red's planes sit in the
    // reverse of the saturated colour's order, and green's and blue's in two more.
    val PRIMARY_SIGNALS =
      listOf(floatArrayOf(0.7f, 0.1f, 0.1f), floatArrayOf(0.1f, 0.7f, 0.1f), floatArrayOf(0.1f, 0.1f, 0.7f))
    const val NEAR_HALF = 0.02f

    // A ramp cell's width and height. It is wide enough that the middle of its chroma is clear of its neighbours.
    const val CELL = 16
    const val CELL_HEIGHT = 2

    // One sixteen-bit step, in ten-bit codes.
    const val SIXTEEN_BIT_STEP = 1f / TEN_TO_SIXTEEN_BITS

    // How far, in ten-bit codes, a decoded signal may sit from the one the shared matrix decodes its codes to, once it
    // is carried as float light and put back on the signal. Each sixteen-bit sample is read exactly, so what is left is
    // PQ's black, which the shared transfer puts just above a signal of zero, and float rounding on top of it. A
    // sixteenth of a sixteen-bit step holds both, and the PQ black test pins it there.
    const val DECODE_BOUND = SIXTEEN_BIT_STEP / 16

    // How close to a half, in ten-bit codes, the shared matrix's code may sit for the encode to round it the other
    // way. The graph works its codes out in double and the shared matrix in float, so the two only part on a code
    // within float rounding of a half. A sixty-fourth of a sixteen-bit step.
    const val ENCODE_TIE = SIXTEEN_BIT_STEP / 64

    // The move in signal that puts every plane's code up by one. The matrix is linear, so it is read once off a mid
    // grey, whose codes either side decode inside the cube.
    val ONE_CODE: FloatArray =
      run {
        val luma = TEN_BIT_LUMA_FLOOR + TEN_BIT_LUMA_RANGE / 2
        val grey = signalFromTenBitCodes(luma, TEN_BIT_CHROMA_MID, TEN_BIT_CHROMA_MID)
        val up = signalFromTenBitCodes(luma + 1, TEN_BIT_CHROMA_MID + 1, TEN_BIT_CHROMA_MID + 1)
        FloatArray(3) { up[it] - grey[it] }
      }

    val GRID = HdrGrid.SIGNALS

    // Red, green, blue and alpha of a picture, each walking every sixteen-bit sample by an odd stride of its own.
    val PICTURE: List<IntArray> =
      listOf(1, 40_503, 12_345, 54_321).mapIndexed { channel, stride ->
        IntArray(HdrGrid.PIXELS) { ((it.toLong() * stride + channel * 7_919) % (SIXTEEN_BIT_MAX + 1)).toInt() }
      }

    const val PICTURE_RGBA = "rgba64le"
  }
}
