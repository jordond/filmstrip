package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.still.frame
import kotlin.time.Duration.Companion.seconds

internal suspend fun probeSource(filmstrip: Filmstrip): MediaInfo? {
  // region probe
  val info: MediaInfo? =
    when (val result = filmstrip.probe(MediaSource.of("/path/to/clip.mp4"))) {
      is ProbeResult.Success -> {
        println("${result.info.duration}, ${result.info.video?.displaySize}")
        result.info
      }
      is ProbeResult.Failure -> {
        println(result.error.message)
        null
      }
    }
  // endregion
  return info
}

internal suspend fun deviceCapabilities(filmstrip: Filmstrip): DeviceCapabilities? {
  // region capabilities
  val capabilities: DeviceCapabilities? =
    when (val result = filmstrip.capabilities()) {
      is CapabilitiesResult.Success -> {
        println("HEVC encoder: ${result.capabilities.encoderFor(VideoCodec.Hevc)}")
        println("HDR: ${result.capabilities.supportsHdrEncoding}")
        result.capabilities
      }
      is CapabilitiesResult.Failure -> {
        println(result.error.message)
        null
      }
    }
  // endregion
  return capabilities
}

internal suspend fun oneFrame(
  filmstrip: Filmstrip,
  composition: Composition,
) {
  // region frame
  when (val result = filmstrip.frame(composition, at = 3.seconds, heightPx = 360)) {
    is FrameResult.Success -> {
      result.image.use { image ->
        println("${image.widthPx}x${image.heightPx} at ${result.presentationTime}")
      }
    }
    is FrameResult.Failure -> {
      println(result.error.message)
    }
  }
  // endregion
}

internal suspend fun oneFrameOfASource(filmstrip: Filmstrip) {
  // region frame-source
  val result = filmstrip.frame(MediaSource.of("/path/to/clip.mp4"), at = 1.seconds)
  // endregion
  (result as? FrameResult.Success)?.image?.close()
}

internal suspend fun severalFrames(
  filmstrip: Filmstrip,
  composition: Composition,
) {
  // region frames
  val positions = List(10) { index -> (index * 2).seconds }

  filmstrip.frames(composition, positions, heightPx = 96).collect { result ->
    when (result) {
      is FrameResult.Success -> {
        result.image.use { image ->
          println("${image.widthPx}x${image.heightPx} at ${result.presentationTime}")
        }
      }
      is FrameResult.Failure -> {
        println(result.error.message)
      }
    }
  }
  // endregion
}
