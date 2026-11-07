package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAudioTimePitchAlgorithmSpectral
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.audioMix
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.duration
import platform.AVFoundation.videoComposition
import platform.CoreMedia.CMTimeGetSeconds
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Builds the item a lowered composition plays through in the preview.
 */
internal fun previewItem(lowered: AvComposition): AVPlayerItem =
  AVPlayerItem(asset = lowered.composition).apply {
    videoComposition = lowered.videoComposition
    audioMix = lowered.audioMix
    // The preview plays the scaled composition through Spectral, where the export pre-stretches its audio to a file.
    // The two agree on length and pitch, not on samples.
    audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmSpectral
  }

/**
 * Reads the duration AVFoundation has settled on for this item, or null while it has settled none.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun AVPlayerItem.settledDuration(): Duration? {
  val seconds = CMTimeGetSeconds(duration)
  if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return null
  return seconds.seconds
}
