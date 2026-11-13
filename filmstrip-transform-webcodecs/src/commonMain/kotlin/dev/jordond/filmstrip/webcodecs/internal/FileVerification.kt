@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.internal.awaitCatching
import dev.jordond.filmstrip.webcodecs.internal.interop.asDouble
import dev.jordond.filmstrip.webcodecs.internal.interop.asString
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop

/**
 * What reading the muxed file back proved: bytes that a demuxer accepts, frames a decoder yields,
 * and the timeline the frames actually cover.
 *
 * @property video The video track the read found, or null on a file that carries none.
 * @property audio The audio track the read found, or null on a file that carries none.
 * @property decodedFrames Video frames on a file that carries video, audio samples on one that
 *   carries only audio.
 */
internal class VerifiedFile(
  val video: VerifiedVideo?,
  val audio: VerifiedAudio?,
  val decodedFrames: Long,
  val durationUs: Double,
)

/**
 * The video track a read back found, as the container names it.
 */
internal class VerifiedVideo(
  val codec: String,
)

/**
 * The audio track a read back found, described by the file rather than by the plan that wrote it.
 *
 * A copy carries the source's packets across without normalising them, so a run that takes that path
 * leaves the render's audio format null while still writing audio. Reading the rate and the channel
 * count back off the file covers both paths with one answer.
 */
internal class VerifiedAudio(
  val codec: String,
  val sampleRate: Int,
  val channelCount: Int,
)

/**
 * Demuxes and decodes the file through a reader that never saw the encoder.
 *
 * Counting bytes proves nothing, because a muxer that writes a broken index writes bytes too. The
 * duration is how far the walked samples reach, which on a file with a longer audio track stops short of the
 * container's own.
 */
internal suspend fun EncodedFile.verify(): VerifiedFile? = verified { readBack() }

/**
 * The same read-back for a file that carries audio and no video, where the video track [verify]
 * looks for is the one thing that is meant to be missing.
 */
internal suspend fun EncodedFile.verifyAudio(): VerifiedFile? = verified { readBackAudio() }

// A file the demuxer refuses outright fails verification the same way one that decodes short does, and for the same
// reason: nobody should be handed it.
private suspend fun verified(read: suspend () -> VerifiedFile?): VerifiedFile? = awaitCatching { read() }.getOrNull()

private suspend fun EncodedFile.readBack(): VerifiedFile? {
  val reader = reader()
  try {
    val codec =
      reader
        .videoTrack()
        ?.getCodec()
        ?.await()
        ?.asString() ?: return null
    val audio = reader.verifiedAudio()
    val stream = reader.frames(0.0, Double.POSITIVE_INFINITY) ?: return null
    var decoded = 0L
    var durationUs = 0.0
    try {
      while (true) {
        val sample = stream.next() ?: break
        try {
          decoded++
          durationUs = maxOf(durationUs, sample.microsecondTimestamp + sample.microsecondDuration)
        } finally {
          sample.close()
        }
      }
    } finally {
      stream.close()
    }
    return VerifiedFile(VerifiedVideo(codec), audio, decoded, durationUs)
  } finally {
    reader.close()
  }
}

private suspend fun EncodedFile.readBackAudio(): VerifiedFile? {
  val reader = reader()
  try {
    val audio = reader.verifiedAudio() ?: return null
    val stream = reader.samples(0.0, Double.POSITIVE_INFINITY) ?: return null
    var decoded = 0L
    var durationUs = 0.0
    try {
      while (true) {
        val sample = stream.next() ?: break
        try {
          decoded++
          durationUs = maxOf(durationUs, sample.microsecondTimestamp + sample.microsecondDuration)
        } finally {
          sample.close()
        }
      }
    } finally {
      stream.close()
    }
    return VerifiedFile(null, audio, decoded, durationUs)
  } finally {
    reader.close()
  }
}

/**
 * The audio track of the file being read back, or null when it carries none.
 */
private suspend fun SourceReader.verifiedAudio(): VerifiedAudio? {
  val track = firstAudioTrack() ?: return null
  val codec = track.getCodec().await()?.asString() ?: return null
  val sampleRate = track.getSampleRate().await().asDouble()
  val channels = track.getNumberOfChannels().await().asDouble()
  return VerifiedAudio(codec, sampleRate.toInt(), channels.toInt())
}
