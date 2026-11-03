package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.filterGraphOverlay
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.awt.image.BufferedImage
import kotlin.test.Test
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

      withClue("$orientation") { tagged shouldBe twin }
      if (orientation.swapsAxes) withClue("$orientation") { tagged shouldNotBe stored }
    }
  }

  private fun commandsOf(png: ByteArray): Sidecar {
    val spec =
      ImageOverlay(ImageSource.ofBytes(png), Corner.BottomEnd, animation = slideIn(OverlayOffset(0f, SLIDE), 1.seconds))
    val resolved = filterGraphOverlay(spec, attributes(null), null).shouldBeInstanceOf<EffectResolution.Resolved>()
    return resolved.effect.fragment.sidecars
      .single()
  }

  private companion object {
    const val SLIDE = 0.25f
  }
}
