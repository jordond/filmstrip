package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How the planner gives every clip its own fit and placement, folded over its clip, track and composition geometry.
 */
class ClipFitPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a clip's scale does not reach the clip after it`() {
    val resolved =
      fixture.resolve(composition(fixture.clip(effects = listOf(Scale(1080, Fit.Crop))), fixture.clip(size = NARROW)))
    val (first, second) = resolved.tracks.single().clips

    first.placement.fit shouldBe Fit.Crop
    second.placement.fit shouldBe Fit.Contain
  }

  @Test
  fun `a track's scale reaches a clip with none of its own`() {
    val track = Track(listOf(fixture.clip(), fixture.clip(size = NARROW)), effects = listOf(Scale(1080, Fit.Stretch)))
    val resolved = fixture.resolve(EditComposition(listOf(track)))

    resolved.tracks
      .single()
      .clips
      .map { it.placement.fit } shouldBe listOf(Fit.Stretch, Fit.Stretch)
  }

  @Test
  fun `a composition scale reaches every clip and outranks a clip's own`() {
    val clips = listOf(fixture.clip(effects = listOf(Scale(720, Fit.Contain))), fixture.clip(size = NARROW))
    val resolved = fixture.resolve(EditComposition(listOf(Track(clips)), effects = listOf(Scale(1080, Fit.Crop))))

    resolved.tracks
      .single()
      .clips
      .map { it.placement.fit } shouldBe listOf(Fit.Crop, Fit.Crop)
  }

  @Test
  fun `each clip is placed by the shared placement against the natural frame`() {
    val crop = Crop(AspectRatio(16, 9), Fit.Contain)
    val resolved = fixture.resolve(composition(fixture.clip(), fixture.clip(size = PORTRAIT, effects = listOf(crop))))
    val second =
      resolved.tracks
        .single()
        .clips
        .last()

    second.placement.drawnRect shouldBe placementOf(PORTRAIT, listOf(listOf(crop)), resolved.naturalSize).drawnRect
    second.placement.leavesBars shouldBe true
  }

  @Test
  fun `a pan over a letterboxing crop names the crop the canvas is built at`() {
    val crop = Crop(AspectRatio(1, 1), Fit.Contain)
    val pan = KenBurns(NormalizedRect.Full, NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f))
    val resolved = fixture.resolve(composition(fixture.clip(effects = listOf(pan, crop))))
    val clip =
      resolved.tracks
        .single()
        .clips
        .single()

    clip.placement.readsCanvas shouldBe true
    clip.canvasEffectIndices shouldBe listOf(0)
  }

  @Test
  fun `a clip whose chain reads no canvas names no crop to build one at`() {
    val crop = Crop(AspectRatio(1, 1), Fit.Contain)
    val resolved = fixture.resolve(composition(fixture.clip(effects = listOf(crop))))

    resolved.tracks
      .single()
      .clips
      .single()
      .canvasEffectIndices shouldBe emptyList()
  }

  private companion object {
    val NARROW = Size(1440, 1080)
    val PORTRAIT = Size(1080, 1350)
  }
}
