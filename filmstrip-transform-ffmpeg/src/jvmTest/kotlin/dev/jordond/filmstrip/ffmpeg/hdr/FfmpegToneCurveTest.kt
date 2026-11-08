package dev.jordond.filmstrip.ffmpeg.hdr

import dev.jordond.filmstrip.color.BT2020_LUMA_B
import dev.jordond.filmstrip.color.BT2020_LUMA_G
import dev.jordond.filmstrip.color.BT2020_LUMA_R
import dev.jordond.filmstrip.color.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.color.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.color.sceneFromHlgSignal
import dev.jordond.filmstrip.color.signalFromTenBitCodes
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.color.ycbcrToRgb
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.lowering.canToneMap
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegTag
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_BITRATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_BT2020_TO_BT709
import dev.jordond.filmstrip.transform.internal.hdr.toneMappedSdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Flat patches of a PQ and an HLG clip tone mapped to SDR and read back against the shared tone curve.
 *
 * The clips are written here, lossless, so each patch sits where the test puts it. PQ has greys at 200, 500 and 800
 * cd/m2, on the knee's straight part and in each Hermite piece, and a colour with 2000 cd/m2 green past the mastering
 * peak. HLG has greys at mid signals. Both have an off-grey colour whose channels take one gain and a colour with no
 * green. On every patch ffmpeg's old Hable route misses by more than the cell,
 * so the cell tells the curve apart from it.
 */
class FfmpegToneCurveTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `PQ patches land on the shared tone curve`() =
    runTest(timeout = TIMEOUT) {
      val greys = listOf(200.0, 500.0, 800.0).map { grey(pqSignalFromNits(it)) }
      val colours =
        listOf(
          Rgb(300.0, 500.0, 100.0).map(::pqSignalFromNits),
          Rgb(1500.0, 2000.0, 1200.0).map(::pqSignalFromNits),
          Rgb(pqSignalFromNits(300.0), 0.0, pqSignalFromNits(100.0)),
        )
      assertOnCurve(HdrTransfer.Pq, greys + colours)
    }

  @Test
  fun `HLG patches land on the shared tone curve`() =
    runTest(timeout = TIMEOUT) {
      assertOnCurve(HdrTransfer.Hlg, listOf(grey(0.5), grey(0.65), grey(0.75), Rgb(0.7, 0.55, 0.3), Rgb(0.6, 0.0, 0.4)))
    }

  private suspend fun assertOnCurve(
    transfer: HdrTransfer,
    patches: List<Rgb>,
  ) {
    if (!fixture.available() || !canToneMap()) return
    val codes = patches.map { tenBitCodesFromSignal(floatArrayOf(it.r.toFloat(), it.g.toFloat(), it.b.toFloat())) }
    val source = File.createTempFile("filmstrip-tone-patches", ".mkv").also { it.delete() }
    writePatches(source, transfer, codes)

    val output = File.createTempFile("filmstrip-tone-curve", ".mp4").also { it.delete() }
    val composition = compositionOf { clip(MediaSource.of(source.absolutePath)) }
    val verdict =
      fixture.filmstrip.plan(
        composition,
        ExportSpec(hdr = HdrMode.ToneMapToSdr, bitrate = COLOR_READ_BITRATE),
      )
    fixture.exported(verdict.shouldBeInstanceOf<Verdict.Capable>().plan, output)
    // The preview takes the grade off the export it stands in for, so its own frame spec asks for nothing.
    val previewed = FilterFrames().frames(composition, Duration.ZERO, 1, grade = ResolvedHdr.ToneMap).single()

    codes.forEachIndexed { index, patch ->
      val decoded = signalFromTenBitCodes(patch[0], patch[1], patch[2])
      val signal = Rgb(decoded[0].toDouble(), decoded[1].toDouble(), decoded[2].toDouble())
      val expected = toneMappedSdr(transfer, signal)
      val hable = hableSdr(transfer, signal)
      val read = eightBitCodesAt(output, PATCH_SIDE * index + PATCH_SIDE / 2 to PATCH_SIDE / 2)
      val written = ycbcrToRgb(ycbcrReading(BT709, BT709, BT709), read[0], read[1], read[2], 8, false)
      CHANNELS.forEachIndexed { channel, (name, of) ->
        withClue("patch $index $name wrote ${written[channel]} where the curve puts ${of(expected)}") {
          abs(written[channel] - of(expected)) shouldBeLessThan TONE_CURVE_CELL
        }
      }
      val rgba = 4 * (PATCH_SIDE / 2 * PATCH_SIDE * codes.size + PATCH_SIDE * index + PATCH_SIDE / 2)
      CHANNELS.forEachIndexed { channel, (name, of) ->
        val shown = (previewed[rgba + channel].toInt() and 0xFF) / 255.0
        withClue("patch $index $name previewed $shown where the curve puts ${of(expected)}") {
          abs(shown - of(expected)) shouldBeLessThan TONE_CURVE_CELL
        }
      }
      withClue("patch $index Hable lands inside the cell on every channel, so the cell cannot see the curve") {
        CHANNELS.any { (_, of) -> abs(of(hable) - of(expected)) > TONE_CURVE_CELL }.shouldBeTrue()
      }
    }
    source.delete()
    output.delete()
  }

  private suspend fun canToneMap(): Boolean {
    val result = ToolchainLocator(FfmpegConfig(), ProcessRunner()).toolchain()
    return result is ToolchainResult.Available && canToneMap(result.toolchain)
  }

  // A second of ten-bit BT.2020 stripes, each PATCH_SIDE square and painted in one patch's codes, tagged with transfer.
  // FFV1 is lossless and native to ffmpeg, so every build this backend supports writes it.
  private fun writePatches(
    file: File,
    transfer: HdrTransfer,
    codes: List<IntArray>,
  ) {
    fun stripes(plane: Int): String =
      codes.dropLast(1).withIndex().reversed().fold(codes.last()[plane].toString()) { rest, (index, patch) ->
        "if(lt(X\\,${PATCH_SIDE * (index + 1)})\\,${patch[plane]}\\,$rest)"
      }
    val size = "${PATCH_SIDE * codes.size}x$PATCH_SIDE"
    val graph =
      "nullsrc=s=$size:r=30:d=1,format=yuv444p10le," +
        "geq=lum=${stripes(0)}:cb=${stripes(1)}:cr=${stripes(2)},format=yuv420p10le," +
        "setparams=color_primaries=bt2020:color_trc=${transfer.ffmpegTag}:colorspace=bt2020nc:range=tv"
    val command =
      listOf(hostFfmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", graph) +
        listOf("-c:v", "ffv1", file.absolutePath)
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val log = process.inputStream.readAllBytes().decodeToString()
    withClue("ffmpeg could not write the patch clip: $log") {
      (process.waitFor() == 0 && file.isFile).shouldBeTrue()
    }
  }

  private fun grey(signal: Double): Rgb = Rgb(signal, signal, signal)

  // The route this backend ran before the shared curve: zscale to linear light with 100 cd/m2 at one, tonemap's
  // Hable on the brightest channel with no desaturation against the transfer's own peak, then BT.709 primaries and
  // the BT.709 OETF.
  private fun hableSdr(
    transfer: HdrTransfer,
    signal: Rgb,
  ): Rgb {
    val linear =
      when (transfer) {
        HdrTransfer.Pq -> {
          signal.map { nitsFromPqSignal(it) / HABLE_WHITE_NITS }
        }
        HdrTransfer.Hlg -> {
          val scene = signal.map(::sceneFromHlgSignal)
          val luminance = scene dot Rgb(BT2020_LUMA_R.toDouble(), BT2020_LUMA_G.toDouble(), BT2020_LUMA_B.toDouble())
          scene * (luminance.pow(HLG_SYSTEM_GAMMA - 1.0) * HLG_NOMINAL_PEAK_NITS / HABLE_WHITE_NITS)
        }
      }
    val peak = (if (transfer == HdrTransfer.Pq) PQ_PEAK_NITS else HLG_NOMINAL_PEAK_NITS) / HABLE_WHITE_NITS
    val brightest = max(linear.r, max(linear.g, linear.b))
    val mapped = linear * (hable(brightest) / hable(peak) / brightest)
    return (TONE_MAP_BT2020_TO_BT709 * mapped).map { bt709Oetf(it.coerceIn(0.0, 1.0)) }
  }

  private fun hable(x: Double): Double = (x * (0.15 * x + 0.05) + 0.004) / (x * (0.15 * x + 0.5) + 0.06) - 0.02 / 0.3

  private fun bt709Oetf(linear: Double): Double = if (linear < 0.018) 4.5 * linear else 1.099 * linear.pow(0.45) - 0.099

  // Y', Cb and Cr averaged over a square at probe, as the eight-bit codes the file stores.
  private fun eightBitCodesAt(
    file: File,
    probe: Pair<Int, Int>,
  ): IntArray {
    val x = probe.first - PROBE_SIDE / 2
    val y = probe.second - PROBE_SIDE / 2
    val command =
      listOf(hostFfmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", file.absolutePath) +
        listOf(
          "-vf",
          "crop=$PROBE_SIDE:$PROBE_SIDE:$x:$y",
          "-vframes",
          "1",
          "-f",
          "rawvideo",
          "-pix_fmt",
          "yuv420p",
          "-",
        )
    val process = ProcessBuilder(command).start()
    val bytes = process.inputStream.readAllBytes()
    process.waitFor()
    val luma = PROBE_SIDE * PROBE_SIDE
    val chroma = luma / 4

    fun mean(
      from: Int,
      count: Int,
    ): Int = (from until from + count).sumOf { bytes[it].toInt() and 0xFF }.div(count.toFloat()).roundToInt()

    return intArrayOf(mean(0, luma), mean(luma, chroma), mean(luma + chroma, chroma))
  }

  private companion object {
    const val BT709 = 1
    const val PROBE_SIDE = 16
    const val PATCH_SIDE = 64
    const val HABLE_WHITE_NITS = 100.0
    val CHANNELS = listOf("red" to Rgb::r, "green" to Rgb::g, "blue" to Rgb::b)

    // Two eight-bit video range codes. On ffmpeg 8.0.1 the widest miss is 0.0055, about 1.2 codes, and Hable's
    // closest patch misses by 0.034.
    const val TONE_CURVE_CELL = 2.0 / 219.0
  }
}
