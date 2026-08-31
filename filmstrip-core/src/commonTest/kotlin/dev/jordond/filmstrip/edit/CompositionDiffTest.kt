package dev.jordond.filmstrip.edit

import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

// diff() and effectsRevision() both read straight off EditComposition, with no device and no
// resolver, so every case here runs on the host with fabricated sources and fabricated effects.
class CompositionDiffTest {
  @Test
  fun `a null previous is structural`() {
    diff(previous = null, next = composition(clip())) shouldBe CompositionDiff.Structural
  }

  @Test
  fun `an untouched composition is equal`() {
    val edit = composition(clip(effects = listOf(fakeEffect(EffectIds.ROTATE, EffectStage.Geometry))))

    diff(edit, edit) shouldBe CompositionDiff.Equal
  }

  @Test
  fun `rebuilding the same edit through a copy method still reports equal`() {
    val crop = fakeEffect(EffectIds.CROP_RECT, EffectStage.Geometry, rect = 0.8f)
    val original = composition(clip(effects = listOf(crop)))
    // withEffects always returns a fresh instance, even when the value handed to it is the one the
    // composition already holds, which is what a UI does when a drag settles back where it started.
    val rebuilt = original.withEffects(original.effects)

    diff(original, rebuilt) shouldBe CompositionDiff.Equal
  }

  @Test
  fun `adding a clip is structural`() {
    val previous = composition(clip())
    val next = composition(clip(), clip(path = "/fixtures/second.mp4"))

    diff(previous, next) shouldBe CompositionDiff.Structural
  }

  @Test
  fun `reordering clips is structural`() {
    val a = clip(path = "/fixtures/a.mp4")
    val b = clip(path = "/fixtures/b.mp4")

    diff(composition(a, b), composition(b, a)) shouldBe CompositionDiff.Structural
  }

  @Test
  fun `changing a clip's source is structural`() {
    val previous = composition(clip(path = "/fixtures/a.mp4"))
    val next = composition(clip(path = "/fixtures/b.mp4"))

    diff(previous, next) shouldBe CompositionDiff.Structural
  }

  @Test
  fun `changing a clip's trim is structural`() {
    val previous = composition(clip(trim = TimeRange(0.seconds, 2.seconds)))
    val next = composition(clip(trim = TimeRange(0.seconds, 3.seconds)))

    diff(previous, next) shouldBe CompositionDiff.Structural
  }

  @Test
  fun `changing a track's start is structural`() {
    val previous = EditComposition(listOf(Track(listOf(clip()), start = 0.seconds)))
    val next = EditComposition(listOf(Track(listOf(clip()), start = 1.seconds)))

    diff(previous, next) shouldBe CompositionDiff.Structural
  }

  @Test
  fun `changing a track's looping is structural`() {
    val previous = EditComposition(listOf(Track(listOf(clip()), looping = false)))
    val next = EditComposition(listOf(Track(listOf(clip()), looping = true)))

    diff(previous, next) shouldBe CompositionDiff.Structural
  }

  @Test
  fun `changing a track's content is structural`() {
    val previous = EditComposition(listOf(Track(listOf(clip()), content = TrackContent.AudioAndVideo)))
    val next = EditComposition(listOf(Track(listOf(clip()), content = TrackContent.Video)))

    diff(previous, next) shouldBe CompositionDiff.Structural
  }

  // A crop commit reads as a parameter change under diff(), because the timeline it plays over has
  // not moved, even though every cached thumbnail is now wrong. effectsRevision() carries that half
  // of the story; see the test below.
  @Test
  fun `committing a crop is parameters only rather than structural`() {
    val previous = composition(clip(effects = listOf(fakeEffect(EffectIds.CROP_RECT, EffectStage.Geometry, rect = 1f))))
    val next = composition(clip(effects = listOf(fakeEffect(EffectIds.CROP_RECT, EffectStage.Geometry, rect = 0.8f))))

    diff(previous, next) shouldBe CompositionDiff.ParametersOnly
  }

  @Test
  fun `changing the fill is parameters only`() {
    val previous = composition(clip(), fill = Fill.Black)
    val next = composition(clip(), fill = Fill.White)

    diff(previous, next) shouldBe CompositionDiff.ParametersOnly
  }

  @Test
  fun `changing the audio mix alone is parameters only`() {
    val previous = composition(clip(), audio = AudioSpec.Keep)
    val next = composition(clip(), audio = AudioSpec.Mute)

    diff(previous, next) shouldBe CompositionDiff.ParametersOnly
  }

  // Rotate and flip both rank in the Geometry stage, at 0 and 1, so the pipeline runs them in the
  // same order regardless of which one was declared first. A comparison that reads the raw list
  // would see two different lists and call this a parameter change; the real answer is that
  // nothing changed at all.
  @Test
  fun `reordering effects that resolve to the same pipeline is equal`() {
    val rotate = fakeEffect(EffectIds.ROTATE, EffectStage.Geometry)
    val flip = fakeEffect(EffectIds.FLIP, EffectStage.Geometry)
    val previous = composition(clip(effects = listOf(rotate, flip)))
    val next = composition(clip(effects = listOf(flip, rotate)))

    diff(previous, next) shouldBe CompositionDiff.Equal
  }

  // Crop and crop-rect share a rank, so their relative order is settled by declaration order, and
  // swapping them genuinely changes which one the pipeline runs first.
  @Test
  fun `reordering same-ranked effects changes the pipeline`() {
    val crop = fakeEffect(EffectIds.CROP, EffectStage.Geometry)
    val cropRect = fakeEffect(EffectIds.CROP_RECT, EffectStage.Geometry)
    val previous = composition(clip(effects = listOf(crop, cropRect)))
    val next = composition(clip(effects = listOf(cropRect, crop)))

    diff(previous, next) shouldBe CompositionDiff.ParametersOnly
  }

  @Test
  fun `a crop commit changes the revision even though it is a parameter change`() {
    val previous = composition(clip(effects = listOf(fakeEffect(EffectIds.CROP_RECT, EffectStage.Geometry, rect = 1f))))
    val next = composition(clip(effects = listOf(fakeEffect(EffectIds.CROP_RECT, EffectStage.Geometry, rect = 0.8f))))

    diff(previous, next) shouldBe CompositionDiff.ParametersOnly
    (previous.effectsRevision() == next.effectsRevision()) shouldBe false
  }

  @Test
  fun `nothing that would change a rendered frame holds the revision`() {
    val effects = listOf(fakeEffect(EffectIds.ROTATE, EffectStage.Geometry))
    val previous = composition(clip(effects = effects), audio = AudioSpec.Keep)
    val next = composition(clip(effects = effects), audio = AudioSpec.Mute)

    previous.effectsRevision() shouldBe next.effectsRevision()
  }

  @Test
  fun `a clip's own audio level never reaches the revision`() {
    val previous = composition(clip(audio = AudioLevel.Mute))
    val next = composition(clip(audio = AudioLevel.Volume(0.4f)))

    previous.effectsRevision() shouldBe next.effectsRevision()
  }

  @Test
  fun `a freshly rebuilt composition with the same edit hashes the same`() {
    val crop = fakeEffect(EffectIds.CROP_RECT, EffectStage.Geometry, rect = 0.8f)
    val original = composition(clip(effects = listOf(crop)))
    val rebuilt = original.withEffects(original.effects)

    original.effectsRevision() shouldBe rebuilt.effectsRevision()
  }

  @Test
  fun `reordering effects that resolve to the same pipeline holds the revision`() {
    val rotate = fakeEffect(EffectIds.ROTATE, EffectStage.Geometry)
    val flip = fakeEffect(EffectIds.FLIP, EffectStage.Geometry)
    val previous = composition(clip(effects = listOf(rotate, flip)))
    val next = composition(clip(effects = listOf(flip, rotate)))

    previous.effectsRevision() shouldBe next.effectsRevision()
  }

  @Test
  fun `reordering same-ranked effects changes the revision`() {
    val crop = fakeEffect(EffectIds.CROP, EffectStage.Geometry)
    val cropRect = fakeEffect(EffectIds.CROP_RECT, EffectStage.Geometry)
    val previous = composition(clip(effects = listOf(crop, cropRect)))
    val next = composition(clip(effects = listOf(cropRect, crop)))

    (previous.effectsRevision() == next.effectsRevision()) shouldBe false
  }

  @Test
  fun `changing the fill changes the revision`() {
    val previous = composition(clip(), fill = Fill.Black)
    val next = composition(clip(), fill = Fill.White)

    (previous.effectsRevision() == next.effectsRevision()) shouldBe false
  }

  @Test
  fun `a structural change also changes the revision`() {
    val previous = composition(clip(trim = TimeRange(0.seconds, 2.seconds)))
    val next = composition(clip(trim = TimeRange(0.seconds, 3.seconds)))

    (previous.effectsRevision() == next.effectsRevision()) shouldBe false
  }

  private fun composition(
    vararg clips: Clip,
    fill: Fill = Fill.Black,
    audio: AudioSpec = AudioSpec.Keep,
  ): EditComposition = EditComposition(listOf(Track(clips.toList())), audio = audio, fill = fill)

  private fun clip(
    path: String = "/fixtures/clip.mp4",
    trim: TimeRange? = null,
    effects: List<EffectSpec> = emptyList(),
    audio: AudioLevel = AudioLevel.Inherit,
  ): Clip = Clip(MediaSource.of(path), trim, effects, audio)

  // A minimal EffectSpec fabricated in-place, since the built-in effects live in filmstrip-effects,
  // a module core cannot depend on. rect stands in for whatever a real effect's own parameter is.
  private fun fakeEffect(
    specId: String,
    specStage: EffectStage,
    rect: Float = 0f,
  ): EffectSpec = FakeEffect(specId, specStage, rect)

  private data class FakeEffect(
    override val id: String,
    override val stage: EffectStage,
    val rect: Float,
  ) : EffectSpec
}
