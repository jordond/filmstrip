package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

class LoweringShareTest {
  private val named = ImageSource.Path("/fixtures/named.gif")
  private val other = ImageSource.Path("/fixtures/other.gif")
  private val composition =
    Composition(
      tracks = listOf(Track(emptyList())),
      effects = listOf(ImageOverlay(named, Corner.TopStart), ImageOverlay(named, Corner.BottomEnd)),
    )

  @Test
  fun aPictureNamedTwiceIsBuiltOnceForTheLowering() {
    val share = LoweringShare<Boolean, Any>()
    var builds = 0
    share.readFor(composition)

    val first = share.valueFor(named, false) { Any().also { builds++ } }
    val second = share.valueFor(named, false) { Any().also { builds++ } }

    builds shouldBe 1
    second shouldBeSameInstanceAs first
  }

  @Test
  fun aPictureIsBuiltOncePerKey() {
    val share = LoweringShare<Boolean, Any>()
    var builds = 0
    share.readFor(composition)

    val plain = share.valueFor(named, false) { Any().also { builds++ } }
    val graded = share.valueFor(named, true) { Any().also { builds++ } }

    builds shouldBe 2
    (graded === plain) shouldBe false
    share.valueFor(named, true) { Any().also { builds++ } } shouldBeSameInstanceAs graded
    builds shouldBe 2
  }

  @Test
  fun aPictureTheLoweringDoesNotNameIsBuiltEveryTime() {
    val share = LoweringShare<Boolean, Any>()
    var builds = 0
    share.readFor(composition)

    share.valueFor(other, false) { Any().also { builds++ } }
    share.valueFor(other, false) { Any().also { builds++ } }

    builds shouldBe 2
  }

  @Test
  fun theNextLoweringBuildsAgain() {
    val share = LoweringShare<Boolean, Any>()
    share.readFor(composition)
    val first = share.valueFor(named, false) { Any() }

    share.readFor(composition)

    (share.valueFor(named, false) { Any() } === first) shouldBe false
  }
}
