package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterNode
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
 * The size and positions an animated overlay is drawn at on the ffmpeg graph, for a picture tagged with each of the
 * eight orientations.
 *
 * The graph turns the picture at the head of its branch, so every size and position its expressions carry is the
 * turned picture's. Each tagged picture is drawn the same way as an untagged one stored at the size
 * [PictureOrientation.orientedSize] gives it.
 */
class OverlayOrientedAnimationTest {
  @Test
  fun `an animated overlay is drawn at the size of its picture turned`() {
    val stored = drawnBy(OrientedPictures.png())
    for (orientation in PictureOrientation.entries) {
      val shown = orientation.orientedSize(OrientedPictures.SIZE)
      val twin = drawnBy(pngOf(BufferedImage(shown.width, shown.height, BufferedImage.TYPE_INT_ARGB)))
      val tagged = drawnBy(OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif))

      withClue("$orientation") { tagged shouldBe twin }
      if (orientation.swapsAxes) withClue("$orientation") { tagged shouldNotBe stored }
    }
  }

  // The scale that sizes the picture and the merge that places it, which carry everything the slide moves.
  private fun drawnBy(png: ByteArray): List<FilterNode> {
    val spec =
      ImageOverlay(ImageSource.ofBytes(png), Corner.BottomEnd, animation = slideIn(OverlayOffset(0f, SLIDE), 1.seconds))
    val fragment =
      filterGraphOverlay(spec, attributes(null), null)
        .shouldBeInstanceOf<EffectResolution.Resolved>()
        .effect.fragment
    return listOf(
      fragment.auxInputs
        .single()
        .chain
        .first { it.name == "scale" },
      checkNotNull(fragment.merge),
    )
  }

  private companion object {
    const val SLIDE = 0.25f
  }
}
