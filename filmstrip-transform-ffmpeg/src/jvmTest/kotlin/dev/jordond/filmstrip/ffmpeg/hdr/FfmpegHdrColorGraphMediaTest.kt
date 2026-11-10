package dev.jordond.filmstrip.ffmpeg.hdr

import dev.jordond.filmstrip.color.TEN_BIT_LUMA_RANGE
import dev.jordond.filmstrip.color.TEN_TO_SIXTEEN_BITS
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.hlgDisplayNitsFromSignal
import dev.jordond.filmstrip.color.hlgSignalFromDisplayNits
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.peakNits
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.signalFromTenBitCodes
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.BRIGHTER
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.support.HdrGrid
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.FLOAT_RGB
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.FLOAT_RGBA
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.FRAME_YUV
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.GRADE_YUV
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.GRADE_YUVA
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.SIDE
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.WIDE_YUVA
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.attributes
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.RawFrame
import dev.jordond.filmstrip.ffmpeg.support.filtered
import dev.jordond.filmstrip.ffmpeg.support.flatCodes
import dev.jordond.filmstrip.ffmpeg.support.flatLight
import dev.jordond.filmstrip.ffmpeg.support.gridSamples
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.planarShorts
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.ffmpeg.support.rendered
import dev.jordond.filmstrip.ffmpeg.support.withoutAlpha
import dev.jordond.filmstrip.ffmpeg.support.writtenChain
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREY
import dev.jordond.filmstrip.test.HdrOverlayCells.WHITE
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The chain a color effect on a kept HDR grade lowers to, driven by the ffmpeg on PATH with no encoder after it.
 *
 * A dense grid of in-gamut codes goes through each effect at 4:4:4, so every pixel keeps its own chroma, and flat
 * colors go through the whole chain, the grade's 4:2:0 step included. Every expected code comes from
 * [signalFromTenBitCodes], the shared grade in [transformNits] and [tenBitCodesFromSignal], so a matrix or a rounding
 * of ffmpeg's own between the codes read and the codes written shows as a miss.
 */
class FfmpegHdrColorGraphMediaTest {
  // A code that sits on a half may round either way, since the graph works it out on float tables and the shared grade
  // in float arithmetic.
  @Test
  fun `each color effect lands every pixel of an in-gamut grid on the codes the shared grade rounds it to`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer -> EFFECTS.flatMap { gridMisses(transfer, it, FLOAT_PRECISION) } }

    withClue("${misses.size} pixels off\n" + misses.take(HdrGrid.MISSES_SHOWN).joinToString("\n")) {
      misses.isEmpty().shouldBeTrue()
    }
  }

  // A saturation past two and a quarter turn of hue mix the most between the tables, and the float32 planes they run
  // on are ffmpeg's limit: on PQ a code lands up to FLOAT32_PLANES past a half, further than FLOAT_PRECISION, at the
  // codes in WIDE_EXTREMES.
  @Test
  fun `the widest mixes land within the float planes' precision and their furthest codes need it`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        WIDE_EXTREMES.keys.flatMap { gridMisses(transfer, it, FLOAT32_PLANES) }
      }

    withClue("${misses.size} pixels off\n" + misses.take(HdrGrid.MISSES_SHOWN).joinToString("\n")) {
      misses.isEmpty().shouldBeTrue()
    }
    WIDE_EXTREMES.forEach { (spec, codes) ->
      val written =
        flatCodes(
          rendered(codes, fragmentOf(spec, HdrTransfer.Pq).writtenChain(), GRADE_YUVA).withoutAlpha(GRADE_YUVA),
          SIDE,
        )
      val what = "${spec.id} ${codes.toList()}"

      gradedMiss(HdrTransfer.Pq, spec, codes, written, what, FLOAT32_PLANES).shouldBeNull()
      withClue("$what landed within float precision, so FLOAT32_PLANES is wider than ffmpeg needs") {
        gradedMiss(HdrTransfer.Pq, spec, codes, written, what, FLOAT_PRECISION).shouldNotBeNull()
      }
    }
  }

  // Each table a grade reads fills lut1d to ffmpeg's MAX_1D_LEVEL, the most it holds, and one entry more is refused.
  @Test
  fun `the grade's tables are as large as lut1d takes`() {
    val fragment = fragmentOf(Brightness(HALF), HdrTransfer.Pq)
    val size = fragment.sidecars.maxOf { sizeOf(it.bytes) }
    val source = "color=c=black:s=${SIDE}x$SIDE:r=1,format=$FLOAT_RGB"

    filtered(emptyList(), source, listOf(lut1dOf(size)), FLOAT_RGB)
    val command =
      listOf(hostFfmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", source) +
        listOf("-vf", "lut1d=file=${tableFile(size + 1)}", "-frames:v", "1", "-f", "null", "-")
    val refused = ProcessBuilder(command).redirectErrorStream(true).start()
    refused.inputStream.readAllBytes()

    withClue("lut1d took a table of ${size + 1} entries") { (refused.waitFor() != 0).shouldBeTrue() }
  }

  @Test
  fun `each color effect writes a flat color at the codes the shared grade rounds it to`() {
    val misses =
      flatCases().mapNotNull { (transfer, spec, codes) ->
        val written =
          flatCodes(
            rendered(codes, fragmentOf(spec, transfer).writtenChain(), GRADE_YUVA).withoutAlpha(GRADE_YUVA),
            SIDE,
          )
        gradedMiss(transfer, spec, codes, written, "$transfer ${spec.id} ${codes.toList()}")
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
  }

  // The overlay's decode reads the frame the color effect wrote, so the light it blends on has to be the light of the
  // codes the shared grade rounds to, short only of the float precision the light is carried in.
  @Test
  fun `an overlay after a color effect reads the codes the color effect wrote`() {
    val misses =
      flatCases().mapNotNull { (transfer, spec, codes) ->
        val nodes = fragmentOf(spec, transfer).writtenChain() + overlayOn(transfer).writtenChain()
        // The overlay's decode ends premultiplied by the frame's alpha, so the light is divided back out by it.
        val written = rendered(codes, nodes, FLOAT_RGBA)
        val alpha =
          ByteBuffer
            .wrap(written)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(3 * SIDE * SIDE)
        val light = flatLight(written.withoutAlpha(FLOAT_RGBA), SIDE).map { it / alpha }
        val read = transfer.signalFromNits(FloatArray(3) { light[it] * transfer.peakNits })
        val expected = gradedCodes(transfer, spec, codes)
        val signal = signalFromTenBitCodes(expected[0], expected[1], expected[2])
        val off = read.indices.maxOf { abs(read[it] - signal[it]) } * TEN_BIT_LUMA_RANGE
        if (off <= FLOAT_PRECISION) {
          null
        } else {
          "$transfer ${spec.id} ${codes.toList()} read ${read.toList()}, expected ${signal.toList()}, $off codes off"
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
  }

  private fun flatCases(): List<Triple<HdrTransfer, EffectSpec, IntArray>> =
    HdrTransfer.entries.flatMap { transfer ->
      COLORS.flatMap { color ->
        val codes = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(color)))
        EFFECTS.map { Triple(transfer, it, codes) }
      }
    }

  // Each pixel of the grid that [spec] writes away from the codes the shared grade rounds it to, by more than
  // [precision] past a half. The grade's own 4:2:0 step is left off and the sixteen-bit samples before it are rounded
  // to the nearest ten-bit code, which is what that step does.
  private fun gridMisses(
    transfer: HdrTransfer,
    spec: EffectSpec,
    precision: Float,
  ): List<String> {
    val chain = fragmentOf(spec, transfer).writtenChain()
    withClue("$transfer ${spec.id} ends 4:2:0") { chain.last() shouldBe FilterNode("format", "pix_fmts" to GRADE_YUVA) }
    val read = gridSamples(GRID_FRAME.rendered(chain.dropLast(1), WIDE_YUVA).withoutAlpha(WIDE_YUVA), GRID_CODES.size)
    return GRID_CODES.indices.mapNotNull { pixel ->
      val written = IntArray(3) { (read[it][pixel] + TEN_TO_SIXTEEN_BITS / 2) / TEN_TO_SIXTEEN_BITS }
      gradedMiss(transfer, spec, GRID_CODES[pixel], written, "$transfer ${spec.id} ${GRID[pixel].toList()}", precision)
    }
  }

  // The entries a .cube file says each of its columns holds.
  private fun sizeOf(cube: ByteArray): Int =
    cube
      .decodeToString()
      .substringBefore("\n")
      .substringAfter(SIZE_KEY)
      .toInt()

  // An identity lut1d of [size] entries, as a node reading the file it is written to.
  private fun lut1dOf(size: Int): FilterNode = FilterNode("lut1d", "file" to tableFile(size))

  private fun tableFile(size: Int): String {
    val file = Files.createTempFile("table", ".cube").toFile().apply { deleteOnExit() }
    val rows = (0 until size).map { it.toDouble() / (size - 1) }.joinToString("\n") { "$it $it $it" }
    file.writeText("$SIZE_KEY$size\n$rows\n")
    return file.path
  }

  // Null when [written] is the codes the shared grade rounds [codes] to, or the codes it rounds to with the graded
  // signal moved by [precision] along any of its channels, which differ only on a plane whose code sits on a half.
  private fun gradedMiss(
    transfer: HdrTransfer,
    spec: EffectSpec,
    codes: IntArray,
    written: IntArray,
    what: String,
    precision: Float = FLOAT_PRECISION,
  ): String? {
    val graded = gradedSignal(transfer, spec, codes)
    val nudge = precision / TEN_BIT_LUMA_RANGE
    val near =
      NUDGES.map { by -> tenBitCodesFromSignal(FloatArray(3) { (graded[it] + by[it] * nudge).coerceIn(0f, 1f) }) }
    return if (written.indices.all { plane -> near.any { it[plane] == written[plane] } }) {
      null
    } else {
      "$what read ${written.toList()}, expected ${tenBitCodesFromSignal(graded).toList()} from ${codes.toList()}"
    }
  }

  private fun gradedCodes(
    transfer: HdrTransfer,
    spec: EffectSpec,
    codes: IntArray,
  ): IntArray = tenBitCodesFromSignal(gradedSignal(transfer, spec, codes))

  // The signal the shared grade writes for a pixel of [codes]: each channel's light, through the matrix on its SDR
  // signal, back onto the transfer's signal. HLG reaches display light per channel, the way the grade reads it.
  private fun gradedSignal(
    transfer: HdrTransfer,
    spec: EffectSpec,
    codes: IntArray,
  ): FloatArray {
    val signal = signalFromTenBitCodes(codes[0], codes[1], codes[2])
    val nits =
      FloatArray(3) {
        when (transfer) {
          HdrTransfer.Pq -> nitsFromPqSignal(signal[it])
          HdrTransfer.Hlg -> hlgDisplayNitsFromSignal(signal[it])
        }
      }
    val graded = checkNotNull(colorMatrixOf(spec)).transformNits(nits[0], nits[1], nits[2], transfer)
    return FloatArray(3) {
      when (transfer) {
        HdrTransfer.Pq -> pqSignalFromNits(graded[it])
        HdrTransfer.Hlg -> hlgSignalFromDisplayNits(graded[it])
      }
    }
  }

  // One flat frame of [codes] at the grade's own 4:2:0, run through [nodes] and written raw as [format].
  private fun rendered(
    codes: IntArray,
    nodes: List<FilterNode>,
    format: String,
  ): ByteArray {
    val source = "format=$FRAME_YUV,geq=lum=${codes[0]}:cb=${codes[1]}:cr=${codes[2]},format=$GRADE_YUV"
    return filtered(emptyList(), "color=c=black:s=${SIDE}x$SIDE:r=1,$source", nodes, format)
  }

  private fun fragmentOf(
    spec: EffectSpec,
    transfer: HdrTransfer,
  ): FilterFragment {
    val resolution = BuiltInEffectResolver().resolve(spec, CAPABILITIES, attributes(transfer, 1.seconds))
    return withClue("$transfer: ${spec.id} resolves") {
      resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
    }.effect.fragment
  }

  private fun overlayOn(transfer: HdrTransfer): FilterFragment =
    fragmentOf(ImageOverlay(ImageSource.ofBytes(pngOf(WHITE, OVERLAY_SIZE)), Corner.TopStart), transfer)

  private companion object {
    // A dimming and a brightening brightness, a flattening and a stretching contrast, and a saturation, which mixes the
    // channels. The stretches push a bright channel past the transfer's peak, where the grade clips.
    val EFFECTS: List<EffectSpec> =
      listOf(Brightness(HALF), Brightness(BRIGHTER), Contrast(HALF), Contrast(BRIGHTER), Saturation(HALF))

    // White, where a gain error is widest, and two colors in the middle of the range.
    val COLORS = listOf(WHITE, MID_GREY, MID_GREEN)

    val GRID = HdrGrid.SIGNALS
    val GRID_CODES = GRID.map { tenBitCodesFromSignal(it) }
    val GRID_FRAME =
      RawFrame(planarShorts((0..2).map { plane -> IntArray(GRID_CODES.size) { GRID_CODES[it][plane] } }), FRAME_YUV)

    // A saturation whose gains reach past two and a quarter turn of hue, whose mix reads every channel by a weight of
    // its own sign, each with the luma, Cb and Cr codes a sweep of every in-gamut code found furthest past a half on
    // PQ.
    val WIDE_EXTREMES: Map<EffectSpec, IntArray> =
      mapOf(
        Saturation(WIDE_SATURATION) to intArrayOf(715, 540, 116),
        HueRotate(QUARTER_TURN) to intArrayOf(772, 273, 626),
      )
    const val WIDE_SATURATION = 2.5f
    const val QUARTER_TURN = 90f

    // How far past a half, in ten-bit codes, a code read off ffmpeg's float32 planes lands on the widest mixes, which
    // is ffmpeg's limit rather than this backend's.
    const val FLOAT32_PLANES = 0.0025f

    // The line a .cube file names its size on.
    const val SIZE_KEY = "LUT_1D_SIZE "

    // How far, in ten-bit codes, a signal carried as float may move from the shared grade's, which works in float too.
    const val FLOAT_PRECISION = 0.001f

    // Every way of moving a signal's three channels down, not at all or up.
    val NUDGES: List<IntArray> = (0 until 27).map { intArrayOf(it % 3 - 1, it / 3 % 3 - 1, it / 9 - 1) }
  }
}
