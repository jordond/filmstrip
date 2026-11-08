@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ShortClipContractTest
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.toList
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The short clip contract in the browser, where nothing outside the backend module can demux a file, so the frames in
 * one are counted from its own sample table.
 */
class WebShortClipMediaTest : ShortClipContractTest(WebClipParity) {
  override suspend fun writtenFrameCount(composition: EditComposition): Long {
    val response = fetch(WebClipParity.exportOf(composition).url).await<WebResponse>()
    val file = DataView(response.arrayBuffer().await<JsAny>())
    return videoSampleCount(file) ?: fail("the file carries no video sample table")
  }

  override suspend fun sourceAt(rate: Int): MediaSource {
    val edit =
      EditComposition(listOf(Track(listOf(Clip(fixtureClip, TimeRange.of(Duration.ZERO, 1_500.milliseconds))))))
    val spec = ExportSpec(frameRate = rate, bitrate = PREVIEW_PARITY_EXPORT_BITRATE)
    val plan =
      when (val verdict = filmstrip.plan(edit, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> fail("the export refused the fixture: ${verdict.reasons.map { it.message }}")
      }
    val statuses = filmstrip.export(plan, MediaSink.Uri("")).toList()
    val success = statuses.filterIsInstance<ExportStatus.Success>().singleOrNull() ?: fail("the export gave $statuses")
    val output = success.output as? MediaSink.Uri ?: fail("the export handed back ${success.output}")
    return MediaSource.Uri(output.uri)
  }
}

// The `stsz` entry count of the first track whose `hdlr` names video, walking moov, trak, mdia, minf and stbl.
private fun videoSampleCount(file: DataView): Long? {
  val moov = file.childrenOf(0, file.byteLength).firstOrNull { it.type == "moov" } ?: return null
  for (trak in file.childrenOf(moov.body, moov.end).filter { it.type == "trak" }) {
    val mdia = file.childrenOf(trak.body, trak.end).firstOrNull { it.type == "mdia" } ?: continue
    val parts = file.childrenOf(mdia.body, mdia.end)
    val hdlr = parts.firstOrNull { it.type == "hdlr" } ?: continue
    if (file.typeAt(hdlr.body + HANDLER_OFFSET) != "vide") continue
    val minf = parts.firstOrNull { it.type == "minf" } ?: return null
    val stbl = file.childrenOf(minf.body, minf.end).firstOrNull { it.type == "stbl" } ?: return null
    val stsz = file.childrenOf(stbl.body, stbl.end).firstOrNull { it.type == "stsz" } ?: return null
    return file.getUint32(stsz.body + SAMPLE_COUNT_OFFSET).toLong()
  }
  return null
}

private class Box(
  val type: String,
  val body: Int,
  val end: Int,
)

private fun DataView.childrenOf(
  start: Int,
  end: Int,
): List<Box> =
  buildList {
    var at = start
    while (at + BOX_HEADER <= end) {
      val size = getUint32(at)
      if (size < BOX_HEADER) fail("the file holds a box of $size bytes at $at")
      add(Box(typeAt(at + BOX_TYPE_OFFSET), at + BOX_HEADER, at + size))
      at += size
    }
  }

private fun DataView.typeAt(at: Int): String =
  (0 until BOX_TYPE_LENGTH)
    .map {
      getUint8(at + it).toChar()
    }.joinToString("")

private const val BOX_HEADER = 8
private const val BOX_TYPE_OFFSET = 4
private const val BOX_TYPE_LENGTH = 4
private const val HANDLER_OFFSET = 8
private const val SAMPLE_COUNT_OFFSET = 8

private external fun fetch(url: String): Promise<WebResponse>

private external interface WebResponse : JsAny {
  fun arrayBuffer(): Promise<JsAny>
}

private external class DataView(
  buffer: JsAny,
) : JsAny {
  val byteLength: Int

  fun getUint8(offset: Int): Int

  fun getUint32(offset: Int): Int
}
