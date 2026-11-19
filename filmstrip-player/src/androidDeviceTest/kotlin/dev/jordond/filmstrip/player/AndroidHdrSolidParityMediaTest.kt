package dev.jordond.filmstrip.player

import android.os.Build
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.HdrParityBackend
import dev.jordond.filmstrip.player.contract.HdrSolidParityContractTest
import dev.jordond.filmstrip.player.internal.HdrPreviewReadback
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.hdrReadbackLimit
import dev.jordond.filmstrip.test.tenBitFrameCodesAt
import java.io.File
import kotlin.time.Duration

/**
 * The HDR solid parity contract on media3, which needs a device with an HDR encoder on API 34 or later.
 */
internal class AndroidHdrSolidParityMediaTest : HdrSolidParityContractTest(AndroidHdrParity)

/**
 * media3's side of the suites that hold a kept HDR grade's preview to its export.
 *
 * The export is decoded back to ten-bit samples by the platform decoder, which never saw the composition.
 */
internal object AndroidHdrParity : HdrParityBackend, ClipParityBackend by AndroidClipParity {
  private val exports = mutableMapOf<Composition, File>()

  override suspend fun hdrFixture(transfer: HdrTransfer): MediaSource =
    androidFixture(
      when (transfer) {
        HdrTransfer.Pq -> "android_export_hdr.mp4"
        HdrTransfer.Hlg -> "android_export_hdr_hlg.mp4"
      },
    )

  override fun hdrReadback(engine: PlayerEngine): HdrPreviewReadback = (engine as Media3PlayerEngine).hdrReadback

  // media3's limit: below API 34 the preview hands back no HDR frame, see hdrReadbackLimit.
  override suspend fun encodesHdr(): Boolean = hdrReadbackLimit(Build.VERSION.SDK_INT) == null && super.encodesHdr()

  override suspend fun exportHdrCodes(
    composition: Composition,
    position: Duration,
    x: Double,
    y: Double,
  ): IntArray {
    val file = exports.getOrPut(composition) { export(composition, HdrMode.KeepHdr) }
    return tenBitFrameCodesAt(file, position).codesAt(x.toFloat(), y.toFloat())
  }
}
