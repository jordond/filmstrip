package dev.jordond.filmstrip.internal

import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever

/**
 * Whether the first sync sample of the video track this retriever opened fails to decode, on a device with a decoder
 * that takes [video].
 *
 * A format no decoder here takes says nothing about its bytes, so it is not decoded and reads as whole. With no format
 * to check, the frame is decoded anyway.
 */
internal fun MediaMetadataRetriever.firstFrameDamaged(video: MediaFormat?): Boolean {
  if (video != null && MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(video) == null) return false
  val frame = getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return true
  frame.recycle()
  return false
}
