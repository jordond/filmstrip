package dev.jordond.filmstrip.docs.landing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.VideoSurface
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun landingComposition(): Composition {
  // region composition
  val composition =
    compositionOf {
      clip(MediaSource.of("beach.mp4")) {
        trim(2.seconds..8.seconds)
        effects {
          saturation(2f)
          contrast(1.15f)
          textOverlay(
            text = "SUMMER '96",
            at = 0.seconds..3.seconds,
            anchor = Anchor.TopCenter,
            animation = fade(inDuration = 500.milliseconds),
          )
        }
        fadeOut(1.seconds)
      }
    }
  // endregion
  return composition
}

internal suspend fun landingExport(composition: Composition) {
  // region export
  val filmstrip = Filmstrip()

  val plan =
    when (val verdict = filmstrip.plan(composition, ExportSpec.Upload)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> return
    }

  filmstrip.export(plan, MediaSink.of("summer96.mp4")).collect { status ->
    when (status) {
      is ExportStatus.Progress -> println("Exporting ${(status.fraction * 100).toInt()}%")
      is ExportStatus.Success -> println("Saved summer96.mp4")
      is ExportStatus.Failure -> println("Export failed")
      else -> Unit
    }
  }
  // endregion
}

// region preview
@Composable
fun Preview(
  filmstrip: Filmstrip,
  composition: Composition,
) {
  val player = rememberFilmstripPlayer(filmstrip, composition)

  Column {
    VideoSurface(player, Modifier.aspectRatio(16f / 9f))
    Row {
      BasicText("Play", Modifier.clickable { player.play() })
      BasicText("Pause", Modifier.clickable { player.pause() })
    }
  }
}
// endregion
