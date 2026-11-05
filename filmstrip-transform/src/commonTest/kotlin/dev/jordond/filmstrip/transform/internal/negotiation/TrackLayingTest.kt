package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.stillHold
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.FakeResolver
import dev.jordond.filmstrip.transform.internal.MAX_LAID_PASSES
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import dev.jordond.filmstrip.transform.internal.fakePlatformEffect
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import dev.jordond.filmstrip.transform.internal.resolve.curveOver
import dev.jordond.filmstrip.transform.internal.resolve.fadeEnvelope
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a track's passes are laid onto the composition timeline with every scope's gain folded in, the trim window each
 * clip opens on, and the refusals a track's levels, fades and pass count reach.
 */
class TrackLayingTest {
  private val fixture = PlannerFixture()

  @Test
  fun `an untrimmed clip opens at zero and runs to the source's end`() {
    val clip = fixture.clip(duration = 6.seconds)

    trimWindow(clip, infoOf(clip)) shouldBe (Duration.ZERO to 6.seconds)
  }

  @Test
  fun `a trim past the source's end is held to it and an empty one keeps nothing`() {
    val past = fixture.clip(duration = 6.seconds, trim = TimeRange.of(2.seconds, 9.seconds))
    val beyond = fixture.clip(duration = 6.seconds, trim = TimeRange.of(7.seconds, 9.seconds))

    trimWindow(past, infoOf(past)) shouldBe (2.seconds to 6.seconds)
    trimWindow(beyond, infoOf(beyond)).shouldBeNull()
  }

  @Test
  fun `a copy opens on the sync sample it moved back to`() {
    val clip = fixture.clip(duration = 6.seconds, trim = TimeRange.of(2.seconds, 5.seconds))

    trimWindow(clip, infoOf(clip), openAt = 1_500.milliseconds) shouldBe (1_500.milliseconds to 5.seconds)
  }

  @Test
  fun `a still opens at zero and holds for as long as its trim kept`() {
    val trim = TimeRange.of(1.seconds, 3_500.milliseconds)
    val still = fixture.imageClip(duration = 5.seconds, trim = trim)
    val emptied = fixture.imageClip(duration = 5.seconds, trim = TimeRange.of(5.seconds, 6.seconds))

    trimWindow(still, infoOf(still)) shouldBe (Duration.ZERO to stillHold(5.seconds, trim))
    trimWindow(emptied, infoOf(emptied)).shouldBeNull()
  }

  @Test
  fun `a track whose levels and fades fit is not refused`() {
    val track = Track(listOf(fixture.clip(fadeIn = 1.seconds, fadeOut = 1.seconds)), fadeIn = 3.seconds)

    trackRefusal(0, track, listOf(2.seconds), 3.seconds).shouldBeNull()
  }

  @Test
  fun `a clip's level and fades are read against its own trim before anything else`() {
    val loud = Track(listOf(Clip(fixture.clip().source, audio = AudioLevel.Volume(-1f))))
    val long = Track(listOf(fixture.clip(fadeIn = 1.seconds, fadeOut = 1_500.milliseconds)))

    trackRefusal(0, loud, listOf(2.seconds), 2.seconds) shouldBe
      "A clip's audio level asks for a negative or non-finite gain, or its envelope has points that fall outside " +
      "the clip's trim or run backwards."
    trackRefusal(0, long, listOf(2.seconds), 3.seconds * MAX_LAID_PASSES) shouldBe
      "A clip's fade is negative, longer than the clip's trim, or its fade in and fade out together run longer " +
      "than the trim, so the two ramps would cross."
  }

  @Test
  fun `a run that lays more passes than one track may is refused by the track's index`() {
    val track = Track(listOf(fixture.clip()), looping = true)

    trackRefusal(3, track, listOf(1.milliseconds), MAX_LAID_PASSES.milliseconds).shouldBeNull()
    trackRefusal(3, track, listOf(1.milliseconds), (MAX_LAID_PASSES + 1).milliseconds) shouldBe
      "The track at index 3 lays more than $MAX_LAID_PASSES passes, which is the most one track may lay. A " +
      "looping track repeats until the composition ends, so a short clip under a long composition asks for a " +
      "pass per repeat. A track laid once lays one pass per clip."
  }

  @Test
  fun `a track's level and fades are read against the whole run`() {
    val loud = Track(listOf(fixture.clip()), audio = AudioLevel.Volume(Float.NaN))
    val crossing = Track(listOf(fixture.clip()), fadeIn = 2.seconds, fadeOut = 2.seconds)

    trackRefusal(0, loud, listOf(2.seconds), 2.seconds) shouldBe
      "A track's audio level asks for a negative or non-finite gain, or its envelope has points that fall " +
      "outside the track or run backwards."
    trackRefusal(0, crossing, listOf(2.seconds), 3.seconds) shouldBe
      "A track's fade is negative, longer than the track, or its fade in and fade out together run longer than " +
      "the track, so the two ramps would cross."
    trackRefusal(0, crossing, listOf(2.seconds), 4.seconds).shouldBeNull()
  }

  @Test
  fun `each clip is laid over its own window and its slot on the timeline`() {
    val first = fixture.clip(duration = 6.seconds, trim = TimeRange.of(500.milliseconds, 2_500.milliseconds))
    val second = fixture.clip(duration = 3.seconds)
    val track = Track(listOf(first, second), start = 1.seconds)

    val clips =
      layer(listOf(first, second), startsAtKeyFrame = true)
        .lay(track, windows(first, second), 5.seconds)
        .track
        .clips

    clips.map { it.start to it.end } shouldBe listOf(500.milliseconds to 2_500.milliseconds, Duration.ZERO to 3.seconds)
    clips.map { it.span } shouldBe
      listOf(TimeRange.of(1.seconds, 3.seconds), TimeRange.of(3.seconds, 6.seconds))
    clips.map { it.sourceIndex } shouldBe listOf(0, 1)
    clips.all { it.startsAtKeyFrame } shouldBe true
  }

  @Test
  fun `the clip's the track's and the composition's levels multiply into one curve`() {
    val clip = Clip(fixture.clip(duration = 2.seconds).source, audio = AudioLevel.Volume(0.5f))
    val track = Track(listOf(clip), audio = AudioLevel.Volume(0.8f))

    val gain =
      layer(
        listOf(clip),
        compositionGain = 0.5f,
      ).lay(track, windows(clip), 2.seconds).track.clips.single().gain

    assertNear(0.5f * 0.8f * 0.5f, gain.gainAt(1.seconds))
  }

  // Halfway up the fade, where a fade read per clip rather than over the run would put the first clip at full level.
  @Test
  fun `a track fade is read over the whole run and lands on each pass where it falls`() {
    val first = fixture.clip(duration = 2.seconds)
    val second = fixture.clip(duration = 2.seconds)
    val track = Track(listOf(first, second), fadeIn = 2.seconds)
    val run = 4.seconds

    val clips = layer(listOf(first, second)).lay(track, windows(first, second), run).track.clips

    val fade = fadeEnvelope(2.seconds, Duration.ZERO).curveOver(run)
    val halfway = fade.gainAt(1.seconds)
    withClue("the fade sits at $halfway halfway up") { (halfway > 0.1f && halfway < 0.9f).shouldBeTrue() }
    assertNear(halfway, clips[0].gain.gainAt(1.seconds))
    assertNear(fade.gainAt(2_500.milliseconds), clips[1].gain.gainAt(500.milliseconds))
  }

  @Test
  fun `a looping pass the run cuts short keeps the clip's own curve up to where it stops`() {
    val clip = fixture.clip(duration = 2.seconds, fadeOut = 1_500.milliseconds)
    val track = Track(listOf(clip), looping = true)

    val passes = layer(listOf(clip)).lay(track, windows(clip), 3.seconds).track.clips

    passes.map { it.end } shouldBe listOf(2.seconds, 1.seconds)
    val curve = fadeEnvelope(Duration.ZERO, 1_500.milliseconds).curveOver(2.seconds)
    assertNear(curve.gainAt(750.milliseconds), passes[1].gain.gainAt(750.milliseconds))
    assertNear(passes[0].gain.gainAt(750.milliseconds), passes[1].gain.gainAt(750.milliseconds))
    passes[1].gain.end shouldBe 1.seconds
  }

  @Test
  fun `clip and track effects lower over the pass and fanned geometry runs on the frame they leave`() {
    val recorder = SpanRecorder()
    val scale = Scale(720)
    val clip = fixture.clip(size = Size(1920, 1080), duration = 2.seconds, effects = listOf(scale))
    val track = Track(listOf(clip), effects = listOf(Sepia(1f)), start = 1.seconds)
    val layer =
      TrackLayer(
        StageLowering(listOf(recorder), { "nothing claimed $it" }),
        fixture.infos(composition(clip)),
        loweringTarget(),
        Size(1920, 1080),
        compositionGeometry = listOf(Rotate(90)),
        naturalSize = Size(1920, 1080),
        compositionGain = 1f,
        startsAtKeyFrame = false,
      )

    val laid = layer.lay(track, windows(clip), 2.seconds)

    val laidClip = laid.track.clips.single()
    laid.lowered.map { it.spec.id }.last() shouldBe EffectIds.ROTATE
    laidClip.effects.map { it.specId } shouldBe laid.lowered.map { it.spec.id }
    recorder.spans.values.toSet() shouldBe setOf(laidClip.span)
    recorder.inputs[EffectIds.ROTATE].shouldNotBeNull() shouldBe frameAfter(scale, Size(1920, 1080))
  }

  private fun infoOf(clip: Clip) = fixture.infos(composition(clip)).getValue(clip.source)

  private fun windows(vararg clips: Clip): List<Pair<Duration, Duration>> =
    clips.map { trimWindow(it, infoOf(it)).shouldNotBeNull() }

  private fun layer(
    clips: List<Clip>,
    compositionGain: Float = 1f,
    startsAtKeyFrame: Boolean = false,
  ) = TrackLayer(
    lowering = StageLowering(listOf(FakeResolver()), { "nothing claimed $it" }),
    infos = fixture.infos(composition(*clips.toTypedArray())),
    target = loweringTarget(),
    layoutFrame = Size(1920, 1080),
    compositionGeometry = emptyList(),
    naturalSize = Size(1920, 1080),
    compositionGain = compositionGain,
    startsAtKeyFrame = startsAtKeyFrame,
  )

  private fun assertNear(
    expected: Float,
    actual: Float,
  ) {
    withClue("expected $expected but the gain was $actual") { (abs(expected - actual) <= 1e-4f).shouldBeTrue() }
  }

  /**
   * Claims everything, keeping the span and the input frame each effect was measured against.
   */
  private class SpanRecorder : EffectResolver {
    val spans = mutableMapOf<String, TimeRange>()
    val inputs = mutableMapOf<String, Size>()

    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution {
      spans[spec.id] = attributes.span
      inputs[spec.id] = attributes.inputSize
      return EffectResolution.Resolved(fakePlatformEffect())
    }
  }
}
