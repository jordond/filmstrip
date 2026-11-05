package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test

class FrameGeometryTest {
  @Test
  fun `a quarter turn swaps the sides and a half turn keeps them`() {
    frameAfter(Rotate(90), LANDSCAPE) shouldBe Size(1080, 1920)
    frameAfter(Rotate(270), LANDSCAPE) shouldBe Size(1080, 1920)
    frameAfter(Rotate(180), LANDSCAPE) shouldBe LANDSCAPE
    frameAfter(Rotate(0), LANDSCAPE) shouldBe LANDSCAPE
    frameAfter(Rotate(90), Size(1081, 721)) shouldBe Size(721, 1081)
  }

  // The bounding box of the turned picture, worked out here from the angle rather than copied, at three angles
  // whose two sides each take a different share of the width and the height.
  @Test
  fun `a free angle grows the frame to the bounding box of the turned picture`() {
    listOf(30, 45, 60).forEach { degrees ->
      val radians = degrees * PI / 180
      val width = LANDSCAPE.width * abs(cos(radians)) + LANDSCAPE.height * abs(sin(radians))
      val height = LANDSCAPE.width * abs(sin(radians)) + LANDSCAPE.height * abs(cos(radians))
      withClue("$degrees degrees") {
        frameAfter(Rotate(degrees), LANDSCAPE) shouldBe Size(even(width), even(height))
      }
    }
  }

  @Test
  fun `a free angle the other way lands on the same frame`() {
    frameAfter(Rotate(-30), LANDSCAPE) shouldBe frameAfter(Rotate(30), LANDSCAPE)
    frameAfter(Rotate(390), LANDSCAPE) shouldBe frameAfter(Rotate(30), LANDSCAPE)
  }

  @Test
  fun `a flip leaves the frame alone`() {
    frameAfter(Flip(FlipAxis.Horizontal), LANDSCAPE) shouldBe LANDSCAPE
  }

  @Test
  fun `a crop rect keeps its fraction of each side`() {
    val rect = NormalizedRect(left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1f)

    frameAfter(CropRect(rect), LANDSCAPE) shouldBe Size(960, 540)
  }

  @Test
  fun `a filling crop keeps the region its aspect retains wherever it is anchored`() {
    val centred = frameAfter(Crop(AspectRatio(1, 1)), WIDE)
    val start = frameAfter(Crop(AspectRatio(1, 1), anchor = Anchor.CenterStart), WIDE)

    centred shouldBe Size(800, 800)
    start shouldBe centred
  }

  @Test
  fun `a filling crop to a taller aspect trims the width`() {
    frameAfter(Crop(AspectRatio(1, 2)), WIDE) shouldBe Size(400, 800)
  }

  @Test
  fun `a contained crop keeps the height and takes the width from the aspect`() {
    frameAfter(Crop(AspectRatio(4, 5), fit = Fit.Contain), WIDE) shouldBe Size(640, 800)
    frameAfter(Crop(AspectRatio(16, 9), fit = Fit.Stretch), PORTRAIT) shouldBe Size(3413, 1920)
  }

  @Test
  fun `a scale sets the height and follows the frame's aspect for the width`() {
    frameAfter(Scale(720), LANDSCAPE) shouldBe Size(1280, 720)
    frameAfter(Scale(540), PORTRAIT) shouldBe Size(304, 540)
  }

  @Test
  fun `a scale of a sliver never drops the width below one pixel`() {
    frameAfter(Scale(10), Size(1, 1000)) shouldBe Size(1, 10)
  }

  @Test
  fun `a spec outside the geometry stage leaves the frame alone`() {
    frameAfter(Brightness(0.2f), LANDSCAPE) shouldBe LANDSCAPE
  }

  // Inside one stage rotate sorts ahead of scale, so a single stage turns then scales the turned
  // frame. Across two stages the clip's scale has to finish before the composition's rotate.
  @Test
  fun `each stage is sorted on its own and the stages run in the order given`() {
    frameThrough(LANDSCAPE, listOf(listOf(Scale(720), Rotate(90)))) shouldBe Size(405, 720)
    frameThrough(LANDSCAPE, listOf(listOf(Scale(720)), listOf(Rotate(90)))) shouldBe Size(720, 1280)
  }

  @Test
  fun `a frame through no stages is the source frame`() {
    frameThrough(LANDSCAPE, emptyList()) shouldBe LANDSCAPE
    frameThrough(LANDSCAPE, listOf(emptyList(), emptyList())) shouldBe LANDSCAPE
  }

  @Test
  fun `a crop after a turn measures against the turned frame`() {
    frameThrough(WIDE, listOf(listOf(Crop(AspectRatio(2, 1)), Rotate(90)))) shouldBe Size(800, 400)
    frameThrough(WIDE, listOf(listOf(Crop(AspectRatio(1, 1)), Rotate(90)))) shouldBe Size(800, 800)
  }
}

private val LANDSCAPE = Size(1920, 1080)

private val PORTRAIT = Size(1080, 1920)

// Two to one, so every crop fraction measured against it is exact in a float.
private val WIDE = Size(1600, 800)

// The nearest pixel, then down to an even one the way the encoder's alignment rounds.
private fun even(side: Double): Int = side.roundToInt().let { it - it % 2 }
