package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import dev.jordond.filmstrip.edit.OutputFrame as Frame

/**
 * An image overlay placed at a point lands its centre there in the file the browser writes.
 */
class BrowserOverlayPointMediaTest {
  @Test
  fun aPictureAtAPointCoversTheRectanglePlacedOnGivesOnALandscapeFrame() = runTest { assertLandsWherePlaced(LANDSCAPE) }

  @Test
  fun aPictureAtAPointCoversTheRectanglePlacedOnGivesOnAPortraitFrame() = runTest { assertLandsWherePlaced(PORTRAIT) }

  private suspend fun assertLandsWherePlaced(frame: Size) {
    val overlay = ImageOverlay(IMAGE, OverlayPosition.At(POINT), scale = SCALE)
    val composition =
      compositionOf {
        solid(AuthoredColors.MID_GREY, LENGTH)
        frame(Frame.Fixed(frame))
        audio(AudioSpec.Remove)
        effects { add(overlay) }
      }

    val written = decodeFrames(outputOf(exportOf(composition, MediaSink.Uri("")))).last()

    val size = Size(written.width, written.height)
    size shouldBe frame
    val rect = overlay.placedOn(size, PICTURE).rectOn(size)
    val edgeX = EDGE_PX / size.width
    val edgeY = EDGE_PX / size.height
    val middleX = (rect.left + rect.right) / 2.0
    val middleY = (rect.top + rect.bottom) / 2.0
    val inside =
      mapOf(
        "the centre" to (middleX to middleY),
        "inside the start edge" to (rect.left + edgeX to middleY),
        "inside the end edge" to (rect.right - edgeX to middleY),
        "inside the top edge" to (middleX to rect.top + edgeY),
        "inside the bottom edge" to (middleX to rect.bottom - edgeY),
      )
    val outside =
      mapOf(
        "past the start edge" to (rect.left - edgeX to middleY),
        "past the end edge" to (rect.right + edgeX to middleY),
        "above the top edge" to (middleX to rect.top - edgeY),
        "below the bottom edge" to (middleX to rect.bottom + edgeY),
      )
    inside.forEach { (what, point) ->
      assertReads(OVERLAY, written.around(point.first, point.second), "$what on $frame", DRIFT)
    }
    outside.forEach { (what, point) ->
      assertReads(FILL, written.around(point.first, point.second), "$what on $frame", DRIFT)
    }
  }

  private companion object {
    val PICTURE = Size(64, 64)
    const val COLOR = 0xFFE67814.toInt()
    val IMAGE: ImageSource = ImageSource.ofBytes(OverlayPixels.pngOf(PICTURE, COLOR))
    val OVERLAY = rgbOf(COLOR)
    val FILL = rgbOf(AuthoredColors.MID_GREY)
    const val SCALE = 0.25f
    val POINT = Anchor(0.3f, 0.6f)
    val LANDSCAPE = Size(640, 360)
    val PORTRAIT = Size(360, 640)
    val LENGTH = 240.milliseconds

    // How far a channel may read off its colour after a 4:2:0 encode. The picture and the solid sit about 100 codes
    // apart on red and blue, so a reading on the wrong side of an edge cannot pass.
    const val DRIFT = 12f

    // How far from an edge a reading is centred, clear of the 4:2:0 chroma a sharp edge smears.
    const val EDGE_PX = 10.0
  }
}
