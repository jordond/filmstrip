package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ShortClipContractTest
import kotlin.test.fail

/**
 * The short clip contract on ffmpeg, where every preview read opens its own window on the source.
 */
class JvmShortClipTest : ShortClipContractTest(JvmClipParity) {
  override val readsEveryWindow: Boolean get() = true

  // One line per video packet of the file, taken without decoding, so a frame is counted as it was written.
  override suspend fun writtenFrameCount(composition: EditComposition): Long {
    val path = jvmExportedFile(composition)
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-i",
        path,
        "-map",
        "0:v:0",
        "-c",
        "copy",
        "-f",
        "framecrc",
        "pipe:1",
      ).redirectErrorStream(true).start()
    val lines =
      process.inputStream
        .readAllBytes()
        .decodeToString()
        .lines()
    if (process.waitFor() != 0) fail("counting the frames of $path failed: $lines")
    return lines.count { it.isNotBlank() && !it.startsWith("#") }.toLong()
  }

  override suspend fun sourceAt(rate: Int): MediaSource = JvmClipParity.movingSourceAt(rate)
}
