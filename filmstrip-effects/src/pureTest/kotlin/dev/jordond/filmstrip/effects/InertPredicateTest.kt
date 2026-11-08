package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.identityColorRuns
import dev.jordond.filmstrip.effects.color.isIdentity
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.keepsWholeFrame
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.drawsNothingWithin
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The predicates that say an effect renders nothing, which the planner reads to leave one out.
 */
class InertPredicateTest {
  @Test
  fun `a brightness of one is an identity color run`() {
    val identity = Brightness(1f)

    listOf(identity).identityColorRuns() shouldContainExactly listOf(identity)
  }

  @Test
  fun `a brightness of a half is not an identity color run`() {
    listOf(Brightness(0.5f)).identityColorRuns().shouldBeEmpty()
  }

  @Test
  fun `a run that multiplies out to the identity counts as a whole`() {
    val up = Brightness(2f)
    val down = Brightness(0.5f)

    listOf(up, Rotate(90), down).identityColorRuns() shouldContainExactly listOf(up, down)
  }

  @Test
  fun `a run that lands mid way is not an identity`() {
    listOf(Brightness(2f), Contrast(0.5f)).identityColorRuns().shouldBeEmpty()
  }

  @Test
  fun `a whole turn of hue is the identity`() {
    val turn = HueRotate(360f)

    listOf(turn).identityColorRuns() shouldContainExactly listOf(turn)
  }

  @Test
  fun `the identity matrix is the identity and a mid matrix is not`() {
    ColorMatrix.Identity.isIdentity shouldBe true
    ColorMatrix(rr = 0.5f).isIdentity shouldBe false
  }

  @Test
  fun `a crop to the aspect the frame has keeps the whole frame`() {
    Crop(AspectRatio(16, 9)).keepsWholeFrame(HD) shouldBe true
  }

  @Test
  fun `a crop to four by three on sixteen by nine does not keep the whole frame`() {
    Crop(AspectRatio(4, 3)).keepsWholeFrame(HD) shouldBe false
  }

  @Test
  fun `a contain crop is not answered here`() {
    Crop(AspectRatio(16, 9), fit = Fit.Contain).keepsWholeFrame(HD) shouldBe false
  }

  @Test
  fun `a zero opacity overlay draws nothing and a half opacity one does`() {
    overlay(opacity = 0f).drawsNothingWithin(SPAN) shouldBe true
    overlay(opacity = 0.5f).drawsNothingWithin(SPAN) shouldBe false
  }

  @Test
  fun `a nan opacity is drawn`() {
    overlay(opacity = Float.NaN).drawsNothingWithin(SPAN) shouldBe false
  }

  @Test
  fun `a window outside the span draws nothing`() {
    overlay(window = TimeRange.of(5.seconds, 6.seconds)).drawsNothingWithin(SPAN) shouldBe true
  }

  @Test
  fun `a window half inside the span draws`() {
    overlay(window = TimeRange.of(3.seconds, 6.seconds)).drawsNothingWithin(SPAN) shouldBe false
  }

  @Test
  fun `empty text draws nothing and a space does`() {
    TextOverlay("").drawsNothingWithin(SPAN) shouldBe true
    TextOverlay(" ").drawsNothingWithin(SPAN) shouldBe false
    TextOverlay("a").drawsNothingWithin(SPAN) shouldBe false
  }

  private fun overlay(
    opacity: Float = 1f,
    window: TimeRange? = null,
  ): ImageOverlay =
    ImageOverlay(ImageSource.of("/logo.png"), Corner.TopStart, opacity = opacity, visibleDuring = window)

  private companion object {
    val HD = Size(1920, 1080)
    val SPAN = TimeRange.of(0.seconds, 4.seconds)
  }
}
