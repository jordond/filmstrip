package dev.jordond.filmstrip.docs.landing

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.TextStyle
import kotlin.time.Duration.Companion.seconds

// The site shows a line tagged `// step: name` only while that step is on, and drops the tag. `// step: none` shows a
// line only while every step is off. A tag on a line of its own tags the line below it.
internal fun tryItComposition(): Composition {
  // region try-it
  val composition =
    compositionOf {
      clip(MediaSource.of("beach.mp4")) {
        // Press TRIM, GRADE or TITLE // step: none
        trim(2.seconds..8.seconds) // step: trim
      }
      // step: grade title
      effects {
        saturation(2f) // step: grade
        contrast(1.15f) // step: grade
        textOverlay( // step: title
          text = "SUMMER '96", // step: title
          style = TextStyle(fontSize = 0.05f), // step: title
          anchor = Anchor(0.5f, 0.18f), // step: title
        ) // step: title
      } // step: grade title
    }
  // endregion
  return composition
}
