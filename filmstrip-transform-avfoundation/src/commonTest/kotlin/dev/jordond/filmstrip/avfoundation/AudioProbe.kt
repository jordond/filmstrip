package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.test.ToneAnalysis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleavedKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.Foundation.NSURL
import kotlin.time.Duration

/**
 * One file's decoded audio, downmixed to mono.
 *
 * A mix is read a window at a time rather than as one number over the whole file, so the decode
 * happens once and every measurement comes out of the same array. Samples are asked for as floats,
 * which is already the `-1f..1f` scale [ToneAnalysis] measures on.
 */
internal class AudioProbe(
  val samples: FloatArray,
  val sampleRate: Int,
) {
  /**
   * How much of [frequencyHz] the [window] starting at [from] carries.
   */
  fun toneOver(
    from: Duration,
    window: Duration,
    frequencyHz: Int,
  ): Float =
    ToneAnalysis.amplitudeOver(
      samples = samples,
      from = indexOf(from),
      length = indexOf(window),
      frequencyHz = frequencyHz,
      sampleRate = sampleRate,
    )

  private fun indexOf(at: Duration): Int = (at.inWholeMicroseconds * sampleRate / MICROSECONDS).toInt()

  private companion object {
    const val MICROSECONDS = 1_000_000L
  }
}

/**
 * Decodes the audio track of the file at [path] to mono float samples.
 *
 * The reader is asked for interleaved 32-bit float linear PCM at [PROBE_SAMPLE_RATE], so the decode
 * lands on one known layout whatever the file's own format is. Resampling moves no tone off its own
 * frequency, so a measurement taken here reads the same as one taken at the file's rate.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun audioOf(path: String): AudioProbe {
  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
  val track =
    asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
      ?: error("$path carries no audio track")

  val output =
    AVAssetReaderTrackOutput(
      track = track,
      outputSettings =
        mapOf(
          AVFormatIDKey to kAudioFormatLinearPCM,
          AVSampleRateKey to PROBE_SAMPLE_RATE.toDouble(),
          AVNumberOfChannelsKey to PROBE_CHANNELS,
          AVLinearPCMBitDepthKey to PROBE_BIT_DEPTH,
          AVLinearPCMIsFloatKey to true,
          AVLinearPCMIsBigEndianKey to false,
          AVLinearPCMIsNonInterleavedKey to false,
        ),
    )

  val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: error("no reader for $path")
  reader.addOutput(output)
  if (!reader.startReading()) error("the audio reader refused to start: ${reader.error?.localizedDescription}")

  val chunks = mutableListOf<FloatArray>()
  try {
    while (true) {
      val buffer = output.copyNextSampleBuffer() ?: break
      try {
        val block = CMSampleBufferGetDataBuffer(buffer) ?: continue
        val bytes = CMBlockBufferGetDataLength(block).toInt()
        if (bytes == 0) continue

        val interleaved = FloatArray(bytes / BYTES_PER_SAMPLE)
        interleaved.usePinned { pinned ->
          val status = CMBlockBufferCopyDataBytes(block, 0u, bytes.toULong(), pinned.addressOf(0))
          if (status != 0) error("could not copy $bytes bytes of audio out of $path, status $status")
        }
        chunks += ToneAnalysis.toMono(interleaved, PROBE_CHANNELS)
      } finally {
        CFRelease(buffer)
      }
    }
  } finally {
    reader.cancelReading()
  }

  val mono = FloatArray(chunks.sumOf { it.size })
  var written = 0
  chunks.forEach { chunk ->
    chunk.copyInto(mono, written)
    written += chunk.size
  }
  return AudioProbe(mono, PROBE_SAMPLE_RATE)
}

/**
 * The rate every probe decodes at, which is the rate the fixtures were written at.
 */
internal const val PROBE_SAMPLE_RATE: Int = 48_000

private const val PROBE_CHANNELS = 2
private const val PROBE_BIT_DEPTH = 32
private const val BYTES_PER_SAMPLE = 4
