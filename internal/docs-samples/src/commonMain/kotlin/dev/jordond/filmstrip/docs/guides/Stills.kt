package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.still.StillBytes
import dev.jordond.filmstrip.still.StillFormat
import dev.jordond.filmstrip.still.StillResult
import dev.jordond.filmstrip.still.StillSpec
import dev.jordond.filmstrip.still.encodeStill
import dev.jordond.filmstrip.still.still
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalFilmstripApi::class)
internal fun photoOnTimeline(): EditComposition {
  // region timeline
  val composition =
    compositionOf {
      image(ImageSource.of("/path/to/title.jpg"), duration = 3.seconds)
      clip(MediaSource.of("/path/to/clip.mp4"))
    }
  // endregion
  return composition
}

internal fun photoSpec(): ExportSpec {
  // region timeline-spec
  val spec = ExportSpec(heightPx = 1080, frameRate = 30)
  // endregion
  return spec
}

internal suspend fun writeStill(
  filmstrip: Filmstrip,
  composition: EditComposition,
) {
  // region still
  val result =
    filmstrip.still(
      composition = composition,
      at = 3.seconds,
      to = MediaSink.of("/path/to/poster.jpg"),
      spec = StillSpec(format = StillFormat.Jpeg, quality = 85, heightPx = 720),
    )

  when (result) {
    is StillResult.Success -> println("Wrote ${result.size} to ${result.output}")
    is StillResult.Failure -> println(result.error.message)
  }
  // endregion
}

internal suspend fun stillOfASource(filmstrip: Filmstrip) {
  // region still-source
  filmstrip.still(MediaSource.of("/path/to/clip.mp4"), at = 1.seconds, to = MediaSink.Temporary)
  // endregion
}

internal suspend fun encodeFrame(frame: FrameResult.Success): ByteArray? {
  // region encode
  val encoded = frame.image.encodeStill(StillSpec(format = StillFormat.Png))
  // endregion
  return (encoded as? StillBytes.Success)?.bytes
}
