package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.InertNotice
import dev.jordond.filmstrip.export.InertReason
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * How the planner takes every part of an edit that renders nothing out before it settles, and names each one.
 *
 * Each inert part gets a notice with its reason and reaches no backend, the same part at a value that changes the
 * frame gets none, and an edit whose only effects did nothing still copies.
 */
class InertPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a brightness of one is left out and named`() {
    val edit = single(fixture.clip(effects = listOf(Brightness(1f))))

    reasonsOf(edit) shouldContainExactly listOf(EffectIds.BRIGHTNESS to InertReason.NoChangeToFrame)
    clipEffectsOf(edit).shouldBeEmpty()
  }

  @Test
  fun `a brightness of a half is kept`() {
    val edit = single(fixture.clip(effects = listOf(Brightness(0.5f))))

    reasonsOf(edit).shouldBeEmpty()
    clipEffectsOf(edit).size shouldBe 1
  }

  @Test
  fun `a spec that declares itself inert is left out and named`() {
    val inert =
      object : EffectSpec {
        override val id = "test.inert"
        override val isInert = true
      }
    val edit = single(fixture.clip(effects = listOf(inert)))

    reasonsOf(edit) shouldContainExactly listOf("test.inert" to InertReason.NoChangeToFrame)
    clipEffectsOf(edit).shouldBeEmpty()
  }

  @Test
  fun `a vhs at zero intensity is left out and at a half is kept`() {
    val silent = single(fixture.clip(effects = listOf(Vhs(intensity = 0f))))
    val kept = single(fixture.clip(effects = listOf(Vhs(intensity = 0.5f))))

    reasonsOf(silent) shouldContainExactly listOf(Vhs.ID to InertReason.NoChangeToFrame)
    clipEffectsOf(silent).shouldBeEmpty()
    reasonsOf(kept).shouldBeEmpty()
    clipEffectsOf(kept).size shouldBe 1
  }

  @Test
  fun `a vhs is refused by name when the filters are not registered`() {
    val edit = single(fixture.clip(effects = listOf(Vhs(intensity = 0.5f))))

    val verdict = fixture.plan(edit, resolvers = listOf(BuiltInEffectResolver()))

    verdict
      .shouldBeInstanceOf<Verdict.Incapable>()
      .reasons
      .joinToString { it.toString() } shouldContain Vhs.ID
  }

  @Test
  fun `a clip brightness a track brightness undoes is left out with it`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(effects = listOf(Brightness(2f)))), effects = listOf(HALF))))

    reasonsOf(edit) shouldContainExactly
      listOf(EffectIds.BRIGHTNESS to InertReason.NoChangeToFrame, EffectIds.BRIGHTNESS to InertReason.NoChangeToFrame)
    clipEffectsOf(edit).shouldBeEmpty()
  }

  @Test
  fun `a clip brightness a track brightness only partly undoes is kept`() {
    val edit =
      Composition(
        listOf(Track(listOf(fixture.clip(effects = listOf(Brightness(2f)))), effects = listOf(Brightness(0.75f)))),
      )

    reasonsOf(edit).shouldBeEmpty()
    clipEffectsOf(edit).size shouldBe 1
  }

  // The track brightness undoes the first clip's and darkens the second, so neither half of the first clip's run can
  // go without the other.
  @Test
  fun `a track brightness that undoes only one clip is kept along with that clip's`() {
    val edit =
      Composition(
        listOf(Track(listOf(fixture.clip(effects = listOf(Brightness(2f))), fixture.clip()), effects = listOf(HALF))),
      )

    reasonsOf(edit).shouldBeEmpty()
  }

  @Test
  fun `a whole turn of hue and an identity matrix are left out`() {
    val edit = single(fixture.clip(effects = listOf(HueRotate(360f), ColorMatrix.Identity)))

    reasonsOf(edit).map { it.second } shouldContainExactly
      listOf(InertReason.NoChangeToFrame, InertReason.NoChangeToFrame)
    clipEffectsOf(edit).shouldBeEmpty()
  }

  @Test
  fun `a crop to the aspect the clip has is left out and one to four by three is kept`() {
    val same = single(fixture.clip(effects = listOf(Crop(AspectRatio(16, 9)))))
    val narrower = single(fixture.clip(effects = listOf(Crop(AspectRatio(4, 3)))))

    reasonsOf(same) shouldContainExactly listOf(EffectIds.CROP to InertReason.NoChangeToFrame)
    clipEffectsOf(same).shouldBeEmpty()
    reasonsOf(narrower).shouldBeEmpty()
  }

  @Test
  fun `a whole turn is left out and a quarter turn is kept`() {
    reasonsOf(single(fixture.clip(effects = listOf(Rotate(360))))) shouldContainExactly
      listOf(EffectIds.ROTATE to InertReason.NoChangeToFrame)
    reasonsOf(single(fixture.clip(effects = listOf(Rotate(90))))).shouldBeEmpty()
  }

  @Test
  fun `an overlay at zero opacity is never visible and one at a half is kept`() {
    val hidden = single(fixture.clip(effects = listOf(overlay(opacity = 0f))))
    val half = single(fixture.clip(effects = listOf(overlay(opacity = 0.5f))))

    reasonsOf(hidden) shouldContainExactly listOf(EffectIds.IMAGE_OVERLAY to InertReason.NeverVisible)
    clipEffectsOf(hidden).shouldBeEmpty()
    reasonsOf(half).shouldBeEmpty()
  }

  @Test
  fun `an overlay whose window falls after the clip is never visible`() {
    val after = single(fixture.clip(effects = listOf(overlay(window = TimeRange.of(7.seconds, 8.seconds)))))
    val halfInside = single(fixture.clip(effects = listOf(overlay(window = TimeRange.of(5.seconds, 8.seconds)))))

    reasonsOf(after).map { it.second } shouldContainExactly listOf(InertReason.NeverVisible)
    reasonsOf(halfInside).shouldBeEmpty()
  }

  // The resolver refuses every text overlay, so text that reached it would leave the plan incapable rather than inert.
  @Test
  fun `empty text is never visible and never reaches a backend`() {
    val edit = single(fixture.clip(effects = listOf(TextOverlay(""))))

    val plan = fixture.plan(edit, resolvers = REFUSES_TEXT).shouldBeInstanceOf<Verdict.Capable>().plan
    plan.inertNotices.map(InertNotice::subjectAndReason) shouldContainExactly
      listOf(EffectIds.TEXT_OVERLAY to InertReason.NeverVisible)
  }

  @Test
  fun `empty text whose window falls after the clip gets one notice`() {
    val late = TextOverlay("", visibleDuring = TimeRange.of(7.seconds, 8.seconds))
    val edit = single(fixture.clip(effects = listOf(late)))

    val plan = fixture.plan(edit, resolvers = REFUSES_TEXT).shouldBeInstanceOf<Verdict.Capable>().plan
    plan.inertNotices.map(InertNotice::subjectAndReason) shouldContainExactly
      listOf(EffectIds.TEXT_OVERLAY to InertReason.NeverVisible)
  }

  @Test
  fun `a letter is kept and so is a space over a plate`() {
    val letter = single(fixture.clip(effects = listOf(TextOverlay("a"))))
    val plated = single(fixture.clip(effects = listOf(TextOverlay(" ", TextStyle(backgroundColor = PLATE)))))

    reasonsOf(letter).shouldBeEmpty()
    reasonsOf(plated).shouldBeEmpty()
    fixture.plan(plated, resolvers = REFUSES_TEXT).shouldBeInstanceOf<Verdict.Incapable>()
  }

  @Test
  fun `a track overlay drawn over the second clip only is kept`() {
    val window = overlay(window = TimeRange.of(7.seconds, 8.seconds))
    val edit = Composition(listOf(Track(listOf(fixture.clip(), fixture.clip()), effects = listOf(window))))

    reasonsOf(edit).shouldBeEmpty()
  }

  @Test
  fun `a fill no clip leaves bars for is left out`() {
    val edit = Composition(listOf(Track(listOf(fixture.clip(), fixture.clip(size = Size(1280, 720))))), fill = BLUR)

    reasonsOf(edit) shouldContainExactly listOf("fill" to InertReason.NoChangeToFrame)
    fixture.resolve(edit).fill shouldBe Fill.Black
  }

  @Test
  fun `a fill behind a clip with alpha is kept even when the clip covers the frame`() {
    val edit = Composition(listOf(Track(listOf(fixture.clip(hasAlpha = true)))), fill = BLUR)

    fixture.resolve(edit).fill shouldBe BLUR
  }

  @Test
  fun `a fill behind a four by three clip is kept`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(), fixture.clip(size = Size(1440, 1080))))), fill = BLUR)

    reasonsOf(edit).shouldBeEmpty()
    fixture.resolve(edit).fill shouldBe BLUR
  }

  @Test
  fun `a fill behind a clip whose own scale crops is left out`() {
    val cropped = fixture.clip(size = Size(1440, 1080), effects = listOf(Scale(1080, Fit.Crop)))
    val edit = Composition(listOf(Track(listOf(fixture.clip(), cropped))), fill = BLUR)

    reasonsOf(edit) shouldContainExactly listOf("fill" to InertReason.NoChangeToFrame)
    fixture.resolve(edit).fill shouldBe Fill.Black
  }

  @Test
  fun `a fill inside the canvas of a letterboxing crop is kept`() {
    val boxed = fixture.clip(effects = listOf(Crop(AspectRatio(1, 1), Fit.Contain)))
    val edit = Composition(listOf(Track(listOf(boxed))), fill = BLUR)

    reasonsOf(edit).shouldBeEmpty()
    fixture.resolve(edit).fill shouldBe BLUR
  }

  @Test
  fun `a fill behind a two pixel bar is kept at every height the edit is lowered at`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(), fixture.clip(size = Size(1918, 1080))))), fill = BLUR)

    noticesAtEveryHeight(edit).forEach { it.shouldBeEmpty() }
  }

  @Test
  fun `a fill behind a bar under a pixel of the natural frame is left out at every height`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(), fixture.clip(size = Size(19200, 10805))))), fill = BLUR)

    noticesAtEveryHeight(edit).forEach { it shouldContainExactly listOf("fill" to InertReason.NoChangeToFrame) }
  }

  @Test
  fun `a fill behind a late track is kept`() {
    val edit =
      Composition(
        listOf(
          Track(listOf(fixture.clip()), start = 1.seconds),
          Track(listOf(fixture.audioOnlyClip()), TrackContent.Audio),
        ),
        fill = BLUR,
      )

    reasonsOf(edit).shouldBeEmpty()
  }

  @Test
  fun `a fade with the audio removed has no stream to apply to`() {
    val clip = fixture.clip(audioRate = 48_000, fadeIn = 1.seconds)
    val edit = Composition(listOf(Track(listOf(clip))), audio = AudioSpec.Remove)

    reasonsOf(edit) shouldContainExactly listOf("fadeIn" to InertReason.NoStreamToApplyTo)
  }

  @Test
  fun `a fade on a clip with no audio has no stream to apply to`() {
    reasonsOf(single(fixture.clip(fadeOut = 1.seconds))) shouldContainExactly
      listOf("fadeOut" to InertReason.NoStreamToApplyTo)
  }

  @Test
  fun `a fade over kept audio is kept`() {
    reasonsOf(single(fixture.clip(audioRate = 48_000, fadeIn = 1.seconds))).shouldBeEmpty()
  }

  @Test
  fun `a clip whose only effects are inert copies`() {
    val edit = single(fixture.clip(audioRate = 48_000, effects = listOf(Brightness(1f), Crop(AspectRatio(16, 9)))))

    val plan = fixture.plan(edit).shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
    plan.copyBlockedBy.shouldBeEmpty()
    plan.inertNotices.size shouldBe 2
  }

  @Test
  fun `a strict export of a clip whose only effects are inert copies`() {
    val edit = single(fixture.clip(audioRate = 48_000, effects = listOf(Brightness(1f))))

    val plan = fixture.plan(edit, ExportSpec(strict = true)).shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
    plan.copyBlockedBy.shouldBeEmpty()
  }

  @Test
  fun `the plan carries the edit as it was written`() {
    val edit = single(fixture.clip(effects = listOf(Rotate(360), Brightness(0.5f))))

    val plan = planOf(edit)
    plan.composition shouldBe edit
    plan.effectOrder.map { it.spec } shouldBe listOf<EffectSpec>(Brightness(0.5f))
  }

  @Test
  fun `a speed on a still is left out and named`() {
    val edit = single(fixture.imageClip(duration = 5.seconds).withSpeed(1.5))

    reasonsOf(edit) shouldContainExactly listOf("speed" to InertReason.NoChangeToFrame)
    fixture
      .resolve(edit)
      .tracks
      .first()
      .clips
      .single()
      .speed shouldBe 1.0
  }

  @Test
  fun `taking an inert effect off a clip keeps its speed`() {
    val edit = single(fixture.clip(effects = listOf(Brightness(1f))).withSpeed(0.7))

    reasonsOf(edit) shouldContainExactly listOf(EffectIds.BRIGHTNESS to InertReason.NoChangeToFrame)
    fixture
      .resolve(edit)
      .tracks
      .first()
      .clips
      .single()
      .speed shouldBe 0.7
  }

  @Test
  fun `a speed on a video clip is not inert`() {
    reasonsOf(single(fixture.clip().withSpeed(0.7))).shouldBeEmpty()
  }

  private fun single(clip: Clip): Composition = Composition(listOf(Track(listOf(clip))))

  private fun planOf(edit: Composition): ExportPlan = fixture.plan(edit).shouldBeInstanceOf<Verdict.Capable>().plan

  private fun reasonsOf(edit: Composition): List<Pair<String, InertReason>> =
    planOf(edit).inertNotices.map(InertNotice::subjectAndReason)

  // The notices at the natural frame, at an export asked for a smaller and a larger frame, and at a preview capped
  // under the natural frame, which lays text out against it.
  private fun noticesAtEveryHeight(edit: Composition): List<List<Pair<String, InertReason>>> =
    listOf(
      fixture.plan(edit),
      fixture.plan(edit, ExportSpec(heightPx = 270)),
      fixture.plan(edit, ExportSpec(heightPx = 2160)),
      fixture.plan(edit, ExportSpec(heightPx = 270), layoutSize = Size(1920, 1080)),
    ).map { verdict ->
      verdict
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.inertNotices
        .map(InertNotice::subjectAndReason)
    }

  private fun clipEffectsOf(edit: Composition) =
    fixture
      .resolve(edit)
      .tracks
      .first()
      .clips
      .first()
      .effects

  private fun overlay(
    opacity: Float = 1f,
    window: TimeRange? = null,
  ): ImageOverlay =
    ImageOverlay(ImageSource.of("/logo.png"), Corner.TopStart, opacity = opacity, visibleDuring = window)

  private companion object {
    val BLUR = Fill.Blurred(radius = 0.1f)
    val HALF = Brightness(0.5f)
    const val PLATE = 0xFF000000.toInt()
    val REFUSES_TEXT = listOf(FakeResolver(refuse = setOf(EffectIds.TEXT_OVERLAY)))
  }
}

private fun InertNotice.subjectAndReason(): Pair<String, InertReason> = subject to reason
