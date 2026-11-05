package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Where a clip's chain of geometry places its content in a crop's canvas and that canvas in the output frame.
 */
class ClipPlacementTest {
  @Test
  fun `a chain with no crop places the whole frame under the clip's fit`() {
    val placement = placementOf(PORTRAIT, listOf(emptyList()), HD)

    placement.fit shouldBe Fit.Contain
    placement.drawnRect shouldBe placement.canvasInFrame
    placement.canvasInFrame.width shouldBe (PORTRAIT.width * containScale(PORTRAIT, HD) / HD.width plusOrMinus EPS)
    placement.canvasInFrame.left shouldBe ((1f - placement.canvasInFrame.width) / 2f plusOrMinus EPS)
    placement.leavesBars shouldBe true
  }

  @Test
  fun `a letterboxing crop places a four by five frame inside a sixteen by nine canvas`() {
    val crop = Crop(AspectRatio(16, 9), Fit.Contain)
    val placement = placementOf(PORTRAIT, listOf(listOf(crop)), HD)
    val canvas = frameAfter(crop, PORTRAIT)

    placement.drawnRect.width shouldBe
      (PORTRAIT.width * containScale(PORTRAIT, canvas) / canvas.width plusOrMinus EPS)
    placement.drawnRect.height shouldBe (1f plusOrMinus EPS)
    placement.drawnRect.left shouldBe ((1f - placement.drawnRect.width) / 2f plusOrMinus EPS)
    placement.drawnSize shouldBe PORTRAIT
    placement.canvasInFrame shouldBe NormalizedRect.Full
    placement.leavesBars shouldBe true
  }

  @Test
  fun `a stretching crop fills its canvas whatever the frame's aspect`() {
    val placement = placementOf(PORTRAIT, listOf(listOf(Crop(AspectRatio(16, 9), Fit.Stretch))), HD)

    placement.drawnRect shouldBe NormalizedRect.Full
    placement.leavesBars shouldBe false
  }

  @Test
  fun `a cropping crop's canvas is the content it keeps`() {
    val placement = placementOf(PORTRAIT, listOf(listOf(Crop(AspectRatio(16, 9)))), HD)

    placement.readsCanvas shouldBe false
    placement.leavesBars shouldBe false
  }

  @Test
  fun `the last scale across every stage is the clip's fit`() {
    val stages = listOf(listOf(Scale(720, Fit.Contain)), listOf(Scale(1080, Fit.Crop)))

    placementOf(PORTRAIT, stages, HD).fit shouldBe Fit.Crop
  }

  @Test
  fun `a crop fit places the canvas past the frame's edges and leaves no bar`() {
    val placement = placementOf(PORTRAIT, listOf(listOf(Scale(1080, Fit.Crop))), HD)

    placement.canvasInFrame.height shouldBe (PORTRAIT.height * coverScale(PORTRAIT, HD) / HD.height plusOrMinus EPS)
    placement.canvasInFrame.top shouldBe ((1f - placement.canvasInFrame.height) / 2f plusOrMinus EPS)
    placement.leavesBars shouldBe false
  }

  @Test
  fun `a crop fit over a letterboxed canvas can cover the frame`() {
    // A sixteen by nine frame boxed into a square canvas, then that square cropped back to sixteen by nine.
    val stages = listOf(listOf(Crop(AspectRatio(1, 1), Fit.Contain), Scale(1080, Fit.Crop)))
    val placement = placementOf(HD, stages, HD)

    placement.drawnRect.top shouldBe (0f plusOrMinus EPS)
    placement.drawnRect.bottom shouldBe (1f plusOrMinus EPS)
    placement.leavesBars shouldBe false
  }

  @Test
  fun `a pan after a letterboxing crop reads the canvas and can bring its bars into view`() {
    val pan = KenBurns(NormalizedRect.Full, NormalizedRect(0f, 0f, 0.5f, 0.5f))
    val stages = listOf(listOf(Crop(AspectRatio(1, 1), Fit.Contain), pan, Scale(1080, Fit.Crop)))
    val placement = placementOf(HD, stages, HD)

    placement.readsCanvas shouldBe true
    placement.leavesBars shouldBe true
  }

  @Test
  fun `a composition rotate after a clip's letterboxing crop reads the canvas`() {
    val stages = listOf(listOf(Crop(AspectRatio(1, 1), Fit.Contain)), listOf(Rotate(90)))

    placementOf(HD, stages, HD).readsCanvas shouldBe true
  }

  @Test
  fun `a flip after a letterboxing crop still draws as one rect`() {
    val boxed = listOf(Crop(AspectRatio(1, 1), Fit.Contain))
    val unflipped = placementOf(HD, listOf(boxed), HD)
    val placement = placementOf(HD, listOf(boxed, listOf(Flip(FlipAxis.Vertical))), HD)

    placement.readsCanvas shouldBe false
    placement.drawnRect.top shouldBe (1f - unflipped.drawnRect.bottom plusOrMinus EPS)
  }

  @Test
  fun `a second letterboxing crop folds into the first`() {
    val square = Crop(AspectRatio(1, 1), Fit.Contain)
    val tall = Crop(AspectRatio(9, 16), Fit.Contain)
    val squareCanvas = frameAfter(square, HD)
    val inSquare = placeIn(HD, squareCanvas, Fit.Contain)
    val squareInTall = placeIn(squareCanvas, frameAfter(tall, squareCanvas), Fit.Contain)
    val placement = placementOf(HD, listOf(listOf(square), listOf(tall)), HD)

    placement.readsCanvas shouldBe false
    placement.drawnRect.height shouldBe (inSquare.height * squareInTall.height plusOrMinus EPS)
    placement.drawnSize shouldBe HD
  }

  // Pins the canvas every whole pixel backend builds. An odd height at four by five lands between two widths.
  @Test
  fun `a canvas is as tall as the frame entering its crop and as wide as its aspect rounds to`() {
    val stages = listOf(listOf(Crop(AspectRatio(4, 5), Fit.Contain)), listOf(Rotate(180)))
    val canvas = placementOf(Size(1366, 767), stages, HD).canvases.single()

    canvas.size shouldBe Size(614, 767)
    canvas.aspect shouldBe 614f / 767f
  }

  // A corner overlay lands on the canvas, the frame every effect after the crop runs on, with or without a pan.
  @Test
  fun `an overlay after a letterboxing crop reads the canvas`() {
    val overlay = TextOverlay("corner", anchor = Anchor.TopEnd)
    val stages = listOf(listOf(Crop(AspectRatio(1, 1), Fit.Contain), overlay))
    val placement = placementOf(HD, stages, HD)

    placement.canvases.single().contentInCanvas shouldBe placeIn(HD, Size(1080, 1080), Fit.Contain)
    placement.drawnRect shouldBe placement.canvasInFrame
  }

  // The square canvas is rotated, so it has to be built, and the tall crop after it boxes the rotated square into a
  // canvas nothing reads, which folds into the drawn rect instead of stretching the square.
  @Test
  fun `a letterboxing crop after a canvas reader boxes the canvas it is handed`() {
    val square = Crop(AspectRatio(1, 1), Fit.Contain)
    val tall = Crop(AspectRatio(9, 16), Fit.Contain)
    val placement = placementOf(HD, listOf(listOf(square), listOf(Rotate(90), tall)), HD)
    val squareCanvas = frameAfter(square, HD)
    val tallCanvas = frameAfter(tall, squareCanvas)

    placement.canvases.single().size shouldBe squareCanvas
    placement.drawnSize shouldBe squareCanvas
    placement.drawnRect shouldBe placeIn(squareCanvas, tallCanvas, Fit.Contain).placedIn(placement.canvasInFrame)
    placement.drawnRect.width * HD.width shouldBe (placement.drawnRect.height * HD.height plusOrMinus 0.5f)
  }

  @Test
  fun `a second canvas is built when a reader follows its crop too`() {
    val square = Crop(AspectRatio(1, 1), Fit.Contain)
    val tall = Crop(AspectRatio(9, 16), Fit.Contain)
    val placement = placementOf(HD, listOf(listOf(square), listOf(Rotate(90), tall), listOf(Rotate(180))), HD)
    val squareCanvas = frameAfter(square, HD)

    placement.canvases.map { it.size } shouldBe listOf(squareCanvas, frameAfter(tall, squareCanvas))
    placement.canvases.last().contentInCanvas shouldBe
      placeIn(squareCanvas, frameAfter(tall, squareCanvas), Fit.Contain)
  }

  private companion object {
    val HD = Size(1920, 1080)
    val PORTRAIT = Size(1080, 1350)
    const val EPS = 1e-4f
  }
}
