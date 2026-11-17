package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.HeardSound
import dev.jordond.filmstrip.player.contract.SeamSoundBackend
import dev.jordond.filmstrip.player.contract.SeamSoundContractTest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.fail

/**
 * The seam sound contract on ffmpeg, which decodes the file it wrote back to samples with ffmpeg itself.
 */
internal object JvmSeamSound : SeamSoundBackend, ClipParityBackend by JvmClipParity {
  override suspend fun soundOf(composition: Composition): HeardSound {
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
        "0:a:0",
        "-ac",
        "1",
        "-ar",
        SAMPLE_RATE.toString(),
        "-f",
        "f32le",
        "pipe:1",
      ).start()
    val bytes = process.inputStream.readAllBytes()
    if (process.waitFor() !=
      0
    ) {
      fail("decoding the audio of $path failed: ${process.errorStream.readAllBytes().decodeToString()}")
    }
    val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return HeardSound(FloatArray(floats.remaining()).also(floats::get), SAMPLE_RATE)
  }

  private const val SAMPLE_RATE = 48_000
}

/**
 * The seam sound cells on ffmpeg.
 */
class JvmSeamSoundMediaTest : SeamSoundContractTest(JvmSeamSound)
