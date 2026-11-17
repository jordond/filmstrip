package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.ShortClipBackend
import dev.jordond.filmstrip.player.contract.ShortClipEverySlotContractTest
import dev.jordond.filmstrip.player.contract.ShortClipOffGridContractTest
import dev.jordond.filmstrip.player.contract.ShortClipSeamContractTest
import dev.jordond.filmstrip.player.contract.ShortClipSlotContractTest
import dev.jordond.filmstrip.player.contract.ShortClipTieContractTest
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.test.fail

/**
 * The short clip contract on ffmpeg, where every preview read opens its own window on the source.
 */
internal object JvmShortClips : ShortClipBackend, ClipParityBackend by JvmClipParity {
  override val readsEveryWindow: Boolean get() = true

  // One line per video packet of the file, taken without decoding, so a frame is counted as it was written.
  override suspend fun writtenFrameCount(composition: Composition): Long {
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

  private val copies = mutableMapOf<Int, MediaSource>()

  // A copy of the moving source's file, so a clip cut from it is cut from another file than the moving source.
  override suspend fun sourceAt(rate: Int): MediaSource =
    copies.getOrPut(rate) {
      val moving = JvmClipParity.movingSourceAt(rate).shouldBeInstanceOf<MediaSource.Path>()
      MediaSource.of(
        File(moving.path)
          .copyTo(
            File.createTempFile("filmstrip-jvm-copy", ".mp4").also {
              it.deleteOnExit()
            },
            overwrite = true,
          ).path,
      )
    }
}

/**
 * The single slot short clip cells on ffmpeg.
 */
class JvmShortClipSlotMediaTest : ShortClipSlotContractTest(JvmShortClips)

/**
 * The every slot short clip cells on ffmpeg.
 */
class JvmShortClipEverySlotMediaTest : ShortClipEverySlotContractTest(JvmShortClips)

/**
 * The half frame tie short clip cells on ffmpeg.
 */
class JvmShortClipTieMediaTest : ShortClipTieContractTest(JvmShortClips)

/**
 * The off grid span short clip cells on ffmpeg.
 */
class JvmShortClipOffGridMediaTest : ShortClipOffGridContractTest(JvmShortClips)

/**
 * The seam short clip cells on ffmpeg.
 */
class JvmShortClipSeamMediaTest : ShortClipSeamContractTest(JvmShortClips)
