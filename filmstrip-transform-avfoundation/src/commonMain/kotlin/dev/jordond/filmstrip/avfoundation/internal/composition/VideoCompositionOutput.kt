package dev.jordond.filmstrip.avfoundation.internal.composition

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.writer.videoReaderSettings
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput

/**
 * A reader output that renders [tracks] of this composition through its video composition, in the pixel format
 * [videoReaderSettings] gives.
 *
 * The export's writer takes its frames from this output, so anything else built on it reads the buffers the encoder is
 * handed. On a kept grade those are ten-bit biplanar video range Y'CbCr.
 */
@InternalFilmstripApi
public fun AvComposition.videoCompositionOutput(tracks: List<*>): AVAssetReaderVideoCompositionOutput =
  AVAssetReaderVideoCompositionOutput(videoTracks = tracks, videoSettings = videoReaderSettings(encodesHdr)).also {
    it.videoComposition = videoComposition
  }
