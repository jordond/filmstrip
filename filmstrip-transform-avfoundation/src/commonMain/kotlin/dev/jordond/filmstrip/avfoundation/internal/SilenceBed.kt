@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.export.AudioFormat
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.time.Duration

/**
 * Lays silence under the whole of a composition, on an audio track of its own.
 *
 * AVFoundation's mix ends where its last audio track does and a composition with no audio track has no mix at all,
 * so the composition's audio effects would stop there or never run. With this track under it the mix covers every
 * frame of the timeline. The silence is one short file in [bytesRoot], written the first time it is needed and laid
 * end to end, since a composition track cannot end on an empty edit.
 *
 * @param duration How long the composition runs.
 * @param format The rate and channel count of the silence, which is what a tap on the track runs its kernels at.
 * @param bytesRoot The directory the file of silence lands under. Every composition laid with one root and format
 * shares the file, so it is left there.
 * @return the track, or null when AVFoundation adds none.
 * @throws AvLoweringFailure when the file cannot be written or read back.
 */
internal fun AVMutableComposition.laySilence(
  duration: Duration,
  format: AudioFormat,
  bytesRoot: String,
): AVMutableCompositionTrack? {
  val rate = format.sampleRate
  val total = duration.inWholeNanoseconds * rate / NANOS_PER_SECOND
  if (total <= 0) return null
  val media = addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid) ?: return null

  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(silenceFile(format, bytesRoot)), options = null)
  val source = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
  source ?: throw AvLoweringFailure(NO_SILENCE)
  val length = SILENCE_SECONDS.toLong() * rate
  var at = 0L
  while (at < total) {
    val frames = minOf(length, total - at)
    val range = CMTimeRangeMake(start = CMTimeMake(0, rate), duration = CMTimeMake(frames, rate))
    if (!media.insertTimeRange(range, ofTrack = source, atTime = CMTimeMake(at, rate), error = null)) {
      throw AvLoweringFailure(NO_SILENCE)
    }
    at += frames
  }
  return media
}

// The path of a 16-bit PCM WAV file of silence at [format], written when it is not there yet.
private fun silenceFile(
  format: AudioFormat,
  bytesRoot: String,
): String {
  val path = "${bytesRoot.trimEnd('/')}/filmstrip-silence-${format.sampleRate}-${format.channelCount}.wav"
  if (NSFileManager.defaultManager.fileExistsAtPath(path)) return path

  val frameBytes = format.channelCount * Short.SIZE_BYTES
  val dataBytes = SILENCE_SECONDS * format.sampleRate * frameBytes
  val bytes = ByteArray(WAV_HEADER_BYTES + dataBytes)
  var at = 0

  fun text(value: String) {
    value.forEach { bytes[at++] = it.code.toByte() }
  }

  fun number(
    value: Int,
    size: Int,
  ) {
    repeat(size) { bytes[at++] = (value shr (it * Byte.SIZE_BITS)).toByte() }
  }
  text("RIFF")
  number(WAV_HEADER_BYTES - RIFF_PREFIX_BYTES + dataBytes, Int.SIZE_BYTES)
  text("WAVE")
  text("fmt ")
  number(FORMAT_CHUNK_BYTES, Int.SIZE_BYTES)
  number(PCM_FORMAT, Short.SIZE_BYTES)
  number(format.channelCount, Short.SIZE_BYTES)
  number(format.sampleRate, Int.SIZE_BYTES)
  number(format.sampleRate * frameBytes, Int.SIZE_BYTES)
  number(frameBytes, Short.SIZE_BYTES)
  number(Short.SIZE_BITS, Short.SIZE_BYTES)
  text("data")
  number(dataBytes, Int.SIZE_BYTES)

  // Written whole and moved into place, so a composition laid at the same moment never opens half a file.
  val written =
    bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }.writeToFile(path, true)
  if (!written) throw AvLoweringFailure(NO_SILENCE)
  return path
}

private const val SILENCE_SECONDS = 5
private const val WAV_HEADER_BYTES = 44
private const val RIFF_PREFIX_BYTES = 8
private const val FORMAT_CHUNK_BYTES = 16
private const val PCM_FORMAT = 1
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NO_SILENCE =
  "The silence the composition's audio effects run over could not be laid under the timeline."
