package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ffmpegExportEngine
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.HdrParityBackend
import dev.jordond.filmstrip.player.contract.HdrSolidParityContractTest
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.player.internal.HdrPreviewReadback
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import kotlinx.coroutines.flow.toList
import java.io.File
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.DurationUnit

internal class JvmHdrSolidParityMediaTest : HdrSolidParityContractTest(JvmHdrParity)

/**
 * ffmpeg's side of the suites that hold a kept HDR grade's preview to its export.
 *
 * The export keeps the grade and is decoded back to ten-bit codes by the host's ffmpeg, a process of its own, so the
 * codes compared against come out of the file rather than out of anything the preview shares.
 */
internal object JvmHdrParity : HdrParityBackend, ClipParityBackend by JvmClipParity {
  private val exports = mutableMapOf<Composition, Pair<String, Size>>()

  override suspend fun hdrFixture(transfer: HdrTransfer): MediaSource =
    MediaSource.of(
      when (transfer) {
        HdrTransfer.Pq -> jvmFixture("android_export_hdr.mp4")
        HdrTransfer.Hlg -> jvmFixture("android_export_hdr_hlg.mp4")
      },
    )

  override fun hdrReadback(engine: PlayerEngine): HdrPreviewReadback = (engine as FfmpegPlayerEngine).hdrReadback

  override suspend fun exportHdrCodes(
    composition: Composition,
    position: Duration,
    x: Double,
    y: Double,
  ): IntArray {
    val (path, size) = exports.getOrPut(composition) { export(composition) }
    val planes = decodeTenBitPlanes(path, position, size)
    val pixel = (size.height * y).toInt() * size.width + (size.width * x).toInt()
    val plane = size.width * size.height
    return IntArray(CODES) { channel ->
      val at = (channel * plane + pixel) * 2
      (planes[at].toInt() and BYTE) or ((planes[at + 1].toInt() and BYTE) shl BITS_PER_BYTE)
    }
  }

  private suspend fun export(composition: Composition): Pair<String, Size> {
    val engine = ffmpegExportEngine(CONTRACT_COMPONENTS)
    val spec = ExportSpec(hdr = HdrMode.KeepHdr, bitrate = PREVIEW_PARITY_EXPORT_BITRATE)
    val plan =
      when (val verdict = engine.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> fail("the HDR export refused the edit: ${verdict.reasons.first().message}")
      }
    val output = File.createTempFile("filmstrip-jvm-hdr-export", ".mp4").also { it.deleteOnExit() }
    val finished = engine.export(plan, MediaSink.of(output.absolutePath)).toList().last()
    if (finished is ExportStatus.Failure) fail("the HDR export failed: ${finished.error.message}")
    return output.absolutePath to plan.output.size
  }

  // Planar luma, Cb and Cr, little endian, chroma brought up to full resolution by the decoder's own scaler.
  private fun decodeTenBitPlanes(
    path: String,
    at: Duration,
    size: Size,
  ): ByteArray {
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-ss",
        at.toDouble(DurationUnit.SECONDS).toString(),
        "-i",
        path,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "yuv444p10le",
        "pipe:1",
      ).start()
    val planes = process.inputStream.readAllBytes()
    val stderr = process.errorStream.readAllBytes().decodeToString()
    if (process.waitFor() != 0) fail("decoding $path at $at failed: $stderr")
    if (planes.size != size.width * size.height * CODES * 2) fail("decoding $path at $at gave ${planes.size} bytes")
    return planes
  }

  private const val CODES = 3
  private const val BYTE = 0xFF
  private const val BITS_PER_BYTE = 8
}
