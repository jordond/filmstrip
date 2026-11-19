package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.HdrParityBackend
import dev.jordond.filmstrip.player.contract.HdrSolidParityContractTest
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.HdrPreviewReadback
import kotlin.time.Duration

internal class WebHdrSolidParityMediaTest : HdrSolidParityContractTest(WebHdrParity)

/**
 * The browser's side of the suites that hold a kept HDR grade's preview to its export.
 *
 * The export is read back through a reader that never saw the encoder, decoded at ten bits and packed by the same pass
 * the export's planes come off, so a code read here is the code the file holds where its chroma blocks agree.
 */
internal object WebHdrParity : HdrParityBackend, ClipParityBackend by WebClipParity {
  private val exports = mutableMapOf<Composition, WebExport>()

  override suspend fun hdrFixture(transfer: HdrTransfer): MediaSource = webHdrFixture(transfer)

  override fun hdrReadback(engine: PlayerEngine): HdrPreviewReadback = (engine as BrowserPlayerEngine).hdrReadback

  override suspend fun exportHdrCodes(
    composition: Composition,
    position: Duration,
    x: Double,
    y: Double,
  ): IntArray {
    val file = exports.getOrPut(composition) { webExported(composition, hdr = HdrMode.KeepHdr) }
    val read = webFileCodes(file, position)
    val at = ((read.size.height * y).toInt() * read.size.width + (read.size.width * x).toInt()) * CODES
    return IntArray(CODES) { read.codes[at + it].toInt() }
  }

  private const val CODES = 3
}
