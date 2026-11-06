package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.color.signalFromTenBitCodes
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.player.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.PreviewPlan
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.hdr.toneMappedSdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The export a player stands in for, on a flat PQ clip written here at a mid-range grey.
 */
class FfmpegExportSpecTest {
  @Test
  fun `a tone map spec brings a live preview onto the shared curve and a bitrate change rebuilds nothing`() =
    contractTest(timeout = TIMEOUT) { scope ->
      val signal = pqSignalFromNits(NITS)
      val codes = tenBitCodesFromSignal(floatArrayOf(signal.toFloat(), signal.toFloat(), signal.toFloat()))
      val clip = writeFlatPq(codes)
      val edit =
        EditComposition(listOf(Track(listOf(Clip(MediaSource.of(clip.absolutePath))))), audio = AudioSpec.Remove)
      val planner = FfmpegPreviewPlanner(CONTRACT_COMPONENTS)
      val autoKeeps =
        planner
          .plan(edit, PreviewQualityPolicy.Full, ExportSpec())
          .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
          .plan.resolved.hdr == ResolvedHdr.Keep
      val toneMapped =
        planner
          .plan(edit, PreviewQualityPolicy.Full, TONE_MAP)
          .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
          .plan
      toneMapped.resolved.hdr shouldBe ResolvedHdr.ToneMap
      toneMapped.info.fidelity
        .single { it.property == OutputProperty.HdrAppearance }
        .fidelity shouldBe
        PreviewFidelity.Exact

      val request =
        ThumbnailRequest(edit, POSITION, null, edit.effectsRevision(), precise = true, exportSpec = TONE_MAP)
      val source = createThumbnailSource(request, CONTRACT_COMPONENTS).shouldNotBeNull()
      val thumbnail =
        suspendCancellableCoroutine { waiting ->
          val handle = source.requestThumbnail(request) { waiting.resume(it) }
          waiting.invokeOnCancellation { handle.cancel() }
        }.shouldBeInstanceOf<ThumbnailResult.Success>()

      val engine = FfmpegPlayerEngine(parent = scope, planner = planner, config = PlayerConfig())
      withEngine(engine) { recorder ->
        engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }
        engine.seekTo(POSITION, SeekAccuracy.Exact)
        awaitContract("the seek to land") { recorder.playheads.lastOrNull() == POSITION }
        val loads = engine.platformLoads

        engine.setExportSpec(TONE_MAP)
        if (autoKeeps) awaitContract("the grade to rebuild") { engine.platformLoads > loads }
        engine.awaitQuiet()
        recorder.playheads.last() shouldBe POSITION

        val frame = engine.readback.awaitFrame(POSITION)
        val centre = 4 * (frame.size.height / 2 * frame.size.width + frame.size.width / 2)
        val decoded = signalFromTenBitCodes(codes[0], codes[1], codes[2])
        val expected =
          toneMappedSdr(HdrTransfer.Pq, Rgb(decoded[0].toDouble(), decoded[1].toDouble(), decoded[2].toDouble()))
        listOf(expected.r, expected.g, expected.b).forEachIndexed { channel, curve ->
          val read = (frame.pixels[centre + channel].toInt() and 0xFF).toDouble()
          withClue("channel $channel read $read where the curve puts ${curve * MAX_CODE}") {
            abs(read - curve * MAX_CODE) shouldBeLessThanOrEqual CURVE_CODES
          }
        }

        // The strip frame of the same spec is the frame the preview shows.
        val strip = thumbnail.image.toRgba8888()
        thumbnail.image.close()
        val stripCentre = 4 * (frame.size.height / 2 * frame.size.width + frame.size.width / 2)
        (0..2).forEach { channel ->
          val shown = (frame.pixels[centre + channel].toInt() and 0xFF).toDouble()
          val drawn = (strip[stripCentre + channel].toInt() and 0xFF).toDouble()
          withClue("channel $channel thumbnail $drawn beside preview $shown") {
            abs(drawn - shown) shouldBeLessThanOrEqual CURVE_CODES
          }
        }

        val settled = engine.platformLoads
        engine.setExportSpec(ExportSpec(hdr = HdrMode.ToneMapToSdr, bitrate = Bitrate.mbps(8)))
        engine.awaitQuiet()
        engine.platformLoads shouldBe settled
      }
      clip.delete()
    }

  // FFV1 is lossless and native to ffmpeg, so every build this backend supports writes it and the grey stays put.
  private fun writeFlatPq(codes: IntArray): File {
    val file = File.createTempFile("filmstrip-export-spec", ".mkv").also { it.delete() }
    val graph =
      "nullsrc=s=64x64:r=30:d=1,format=yuv444p10le," +
        "geq=lum=${codes[0]}:cb=${codes[1]}:cr=${codes[2]},format=yuv420p10le," +
        "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv"
    val process =
      ProcessBuilder(
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        graph,
        "-c:v",
        "ffv1",
        file.path,
      ).redirectErrorStream(true)
        .start()
    val log = process.inputStream.readAllBytes().decodeToString()
    withClue("ffmpeg could not write the clip: $log") { (process.waitFor() == 0 && file.isFile).shouldBeTrue() }
    return file
  }

  private companion object {
    const val NITS = 500.0
    const val MAX_CODE = 255.0

    // What every backend's tone map meets against the shared curve, in 8-bit codes.
    const val CURVE_CODES = 2.0
    val POSITION = 200.milliseconds
    val TIMEOUT = 3.minutes
    val TONE_MAP = ExportSpec(hdr = HdrMode.ToneMapToSdr)
  }
}
