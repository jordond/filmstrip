package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effects.overlay.Driven
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.commandsFor
import dev.jordond.filmstrip.effects.overlay.runWithin
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.test.OrientedPictures
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The commands an animated overlay's branch is driven by on the ffmpeg graph, for a picture tagged with each of the
 * eight orientations.
 *
 * The graph turns the picture at the head of its branch, so every size and position the commands carry is the turned
 * picture's. Each tagged picture is driven the same way as an untagged one stored at the size
 * [PictureOrientation.orientedSize] gives it.
 */
class OverlayOrientedCommandsTest {
  @Test
  fun `an animated overlay is driven at the size of its picture turned`() {
    val stored = commandsOf(OrientedPictures.png())
    for (orientation in PictureOrientation.entries) {
      val shown = orientation.orientedSize(OrientedPictures.SIZE)
      val twin = commandsOf(pngOf(BufferedImage(shown.width, shown.height, BufferedImage.TYPE_INT_ARGB)))
      val tagged = commandsOf(OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif))

      assertEquals(twin.sidecar, tagged.sidecar, "$orientation")
      if (orientation.swapsAxes) assertNotEquals(stored.sidecar, tagged.sidecar, "$orientation")
    }
  }

  private fun commandsOf(png: ByteArray): Driven.Commands {
    val spec =
      ImageOverlay(ImageSource.ofBytes(png), Corner.BottomEnd, animation = slideIn(OverlayOffset(0f, SLIDE), 1.seconds))
    val attributes = attributes(null)
    val driven = spec.commandsFor(attributes, spec.runWithin(attributes.span), false, null, null)
    return assertIs<Driven.Commands>(driven)
  }

  private companion object {
    const val SLIDE = 0.25f
  }
}
