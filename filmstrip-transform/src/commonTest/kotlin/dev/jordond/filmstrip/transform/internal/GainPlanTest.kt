package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.EnvelopeAnchor
import dev.jordond.filmstrip.edit.EnvelopePoint
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the planner folds volume levels, envelopes and fades into one gain curve per clip.
 *
 * Covers levels and mutes multiplying down the scopes, envelopes and fades combining pointwise, fades that are refused
 * by name, and gain keeping an export off the copy path.
 */
class GainPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `levels multiply down the scopes`() {
    val composition =
      EditComposition(
        tracks =
          listOf(
            Track(
              listOf(fixture.clip(audioRate = AUDIO_RATE).withAudio(AudioLevel.Volume(0.5f))),
              audio = AudioLevel.Volume(0.5f),
            ),
          ),
        audio = AudioSpec.Volume(0.5f),
      )

    fixture
      .resolve(composition)
      .tracks
      .single()
      .clips
      .single()
      .gain
      .constant shouldBe 0.125f
  }

  @Test
  fun `a mute at any scope silences everything below it`() {
    val composition =
      EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), audio = AudioLevel.Mute)))

    fixture
      .resolve(composition)
      .tracks
      .single()
      .clips
      .single()
      .gain
      .constant shouldBe 0f
  }

  @Test
  fun `a constant fold stays one flat segment`() {
    val composition =
      EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE).withAudio(AudioLevel.Volume(0.5f))))))

    val gain = firstClipOf(fixture.resolve(composition)).gain
    gain.segments.size shouldBe 1
    gain.isConstant shouldBe true
  }

  @Test
  fun `an envelope ramps between its points and holds outside them`() {
    val composition =
      EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE).withAudio(fadeIn(2_000.milliseconds))))))

    val gain = firstClipOf(fixture.resolve(composition)).gain
    gain.isConstant shouldBe false
    gain.gainAt(Duration.ZERO) shouldBe 0f
    gain.gainAt(1_000.milliseconds) shouldBe 0.5f
    gain.gainAt(2_000.milliseconds) shouldBe 1f
    gain.gainAt(5_000.milliseconds) shouldBe 1f
  }

  @Test
  fun `an end anchored point is placed against the trimmed length`() {
    val trimmed = fixture.clip(audioRate = AUDIO_RATE, trim = TimeRange.of(1_000.milliseconds, 5_000.milliseconds))
    val composition = EditComposition(listOf(Track(listOf(trimmed.withAudio(fadeOut(2_000.milliseconds))))))

    // The clip keeps four seconds, so the ramp runs across the last two of them and not the source's.
    val gain = firstClipOf(fixture.resolve(composition)).gain
    gain.gainAt(2_000.milliseconds) shouldBe 1f
    gain.gainAt(3_000.milliseconds) shouldBe 0.5f
    gain.gainAt(4_000.milliseconds) shouldBe 0f
  }

  @Test
  fun `a constant scope scales an envelope rather than replacing it`() {
    val composition =
      EditComposition(
        tracks =
          listOf(
            Track(
              listOf(fixture.clip(audioRate = AUDIO_RATE).withAudio(fadeIn(2_000.milliseconds))),
              audio = AudioLevel.Volume(0.5f),
            ),
          ),
        audio = AudioSpec.Volume(0.5f),
      )

    val gain = firstClipOf(fixture.resolve(composition)).gain
    gain.gainAt(Duration.ZERO) shouldBe 0f
    gain.gainAt(1_000.milliseconds) shouldBe 0.125f
    gain.gainAt(2_000.milliseconds) shouldBe 0.25f
  }

  @Test
  fun `two envelopes multiply pointwise`() {
    // A clip rising across its whole length under a track falling across the same run, so the
    // product is a curve rather than a line and the midpoint is the only place that proves it.
    val composition =
      EditComposition(
        listOf(
          Track(
            clips = listOf(fixture.clip(audioRate = AUDIO_RATE).withAudio(fadeIn(6_000.milliseconds))),
            audio = fadeOut(6_000.milliseconds),
          ),
        ),
      )

    val gain = firstClipOf(fixture.resolve(composition)).gain
    gain.gainAt(Duration.ZERO) shouldBe 0f
    gain.gainAt(3_000.milliseconds) shouldBe 0.25f
    gain.gainAt(6_000.milliseconds) shouldBe 0f
    // Off a breakpoint the chords stand in for a quadratic, within the error the curve documents.
    gain.gainAt(2_900.milliseconds) shouldBe (0.24972f plusOrMinus 0.002f)
  }

  @Test
  fun `a track envelope is read against the whole run of clips`() {
    // Two six second clips make a twelve second track, so a fade over the track's first half ends
    // where the second clip starts rather than where the first one does.
    val composition =
      EditComposition(
        listOf(
          Track(
            clips = listOf(fixture.clip(audioRate = AUDIO_RATE), fixture.clip(audioRate = AUDIO_RATE)),
            audio = fadeIn(6_000.milliseconds),
          ),
        ),
      )

    val clips =
      fixture
        .resolve(composition)
        .tracks
        .single()
        .clips
    clips.first().gain.gainAt(3_000.milliseconds) shouldBe 0.5f
    clips.last().gain.gainAt(Duration.ZERO) shouldBe 1f
    clips.last().gain.isConstant shouldBe true
  }

  @Test
  fun `a fade multiplies a hand written envelope rather than landing among its points`() {
    // Two seconds falling from one to a half, under a fade in across the first of them. A polyline
    // through the union of the two would read one at a second in, where the product reads 0.75.
    val envelope =
      AudioLevel.Envelope(listOf(EnvelopePoint(Duration.ZERO, 1f), EnvelopePoint(2_000.milliseconds, 0.5f)))
    val faded =
      fixture
        .clip(
          audioRate = AUDIO_RATE,
          trim = TimeRange.of(Duration.ZERO, 2_000.milliseconds),
          fadeIn = 1_000.milliseconds,
        ).withAudio(envelope)

    fixture.plan(composition(faded)).shouldBeInstanceOf<Verdict.Capable>()
    val gain = firstClipOf(fixture.resolve(composition(faded))).gain
    gain.gainAt(Duration.ZERO) shouldBe 0f
    gain.gainAt(500.milliseconds) shouldBe (0.4375f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(1_000.milliseconds) shouldBe (0.75f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(1_500.milliseconds) shouldBe (0.625f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(2_000.milliseconds) shouldBe (0.5f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
  }

  @Test
  fun `a fade out multiplies a hand written envelope across the overlap`() {
    // An envelope rising across the whole clip under a fade out over its last second, read in the
    // middle of the overlap rather than at either end of it.
    val envelope =
      AudioLevel.Envelope(listOf(EnvelopePoint(Duration.ZERO, 0.5f), EnvelopePoint(2_000.milliseconds, 1f)))
    val faded =
      fixture
        .clip(
          audioRate = AUDIO_RATE,
          trim = TimeRange.of(Duration.ZERO, 2_000.milliseconds),
          fadeOut = 1_000.milliseconds,
        ).withAudio(envelope)

    val gain = firstClipOf(fixture.resolve(composition(faded))).gain
    gain.gainAt(1_000.milliseconds) shouldBe (0.75f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(1_500.milliseconds) shouldBe (0.4375f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(2_000.milliseconds) shouldBe 0f
  }

  @Test
  fun `a fade under a volume rises to that volume`() {
    val faded = fixture.clip(audioRate = AUDIO_RATE, fadeIn = 2_000.milliseconds).withAudio(AudioLevel.Volume(0.3f))

    val gain = firstClipOf(fixture.resolve(composition(faded))).gain
    gain.gainAt(Duration.ZERO) shouldBe 0f
    gain.gainAt(1_000.milliseconds) shouldBe (0.15f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(2_000.milliseconds) shouldBe (0.3f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(5_000.milliseconds) shouldBe (0.3f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
  }

  @Test
  fun `a fade over silence stays silent`() {
    val faded =
      fixture
        .clip(
          audioRate = AUDIO_RATE,
          fadeIn = 2_000.milliseconds,
          fadeOut = 2_000.milliseconds,
        ).withAudio(AudioLevel.Mute)

    val gain = firstClipOf(fixture.resolve(composition(faded))).gain
    gain.gainAt(Duration.ZERO) shouldBe 0f
    gain.gainAt(1_000.milliseconds) shouldBe 0f
    gain.gainAt(3_000.milliseconds) shouldBe 0f
    gain.gainAt(5_000.milliseconds) shouldBe 0f
  }

  @Test
  fun `a track fade multiplies into every clip window of the track`() {
    // Two six second clips make a twelve second track, so a fade across the whole of it is still
    // climbing when the second clip opens and has to be read in that clip's own time.
    val composition =
      EditComposition(
        listOf(
          Track(
            clips = listOf(fixture.clip(audioRate = AUDIO_RATE), fixture.clip(audioRate = AUDIO_RATE)),
            fadeIn = 12_000.milliseconds,
          ),
        ),
      )

    val clips =
      fixture
        .resolve(composition)
        .tracks
        .single()
        .clips
    clips.first().gain.gainAt(3_000.milliseconds) shouldBe (0.25f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    clips.last().gain.gainAt(3_000.milliseconds) shouldBe (0.75f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
  }

  @Test
  fun `a fade in and a fade out that exactly fill the clip meet at its apex`() {
    // Three seconds up and three down over a six second clip, the longest pair that fits. The two
    // ramps meet rather than cross, so the apex still reaches the level underneath them.
    val faded = fixture.clip(audioRate = AUDIO_RATE, fadeIn = 3_000.milliseconds, fadeOut = 3_000.milliseconds)

    fixture.plan(composition(faded)).shouldBeInstanceOf<Verdict.Capable>()
    val gain = firstClipOf(fixture.resolve(composition(faded))).gain
    gain.gainAt(Duration.ZERO) shouldBe 0f
    gain.gainAt(1_500.milliseconds) shouldBe (0.5f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(3_000.milliseconds) shouldBe (1f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(4_500.milliseconds) shouldBe (0.5f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gain.gainAt(6_000.milliseconds) shouldBe 0f
  }

  @Test
  fun `a negative fade is refused by name`() {
    val verdict = fixture.plan(composition(fixture.clip(audioRate = AUDIO_RATE, fadeIn = -1_000.milliseconds)))

    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "A clip's fade is negative, longer than the clip, or its fade in and fade out together run " +
      "longer than the clip, so the two ramps would cross."
  }

  @Test
  fun `a fade longer than the clip is refused by name`() {
    val verdict = fixture.plan(composition(fixture.clip(audioRate = AUDIO_RATE, fadeIn = 9_000.milliseconds)))

    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "A clip's fade is negative, longer than the clip, or its fade in and fade out together run " +
      "longer than the clip, so the two ramps would cross."
  }

  @Test
  fun `a fade in and a fade out that would cross are refused by name`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(audioRate = AUDIO_RATE, fadeIn = 4_000.milliseconds, fadeOut = 4_000.milliseconds)),
      )

    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "A clip's fade is negative, longer than the clip, or its fade in and fade out together run " +
      "longer than the clip, so the two ramps would cross."
  }

  @Test
  fun `a track fade longer than the track is refused by name`() {
    val composition =
      EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), fadeOut = 9_000.milliseconds)))

    val error =
      fixture
        .plan(composition)
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "A track's fade is negative, longer than the track, or its fade in and fade out together " +
      "run longer than the track, so the two ramps would cross."
  }

  @Test
  fun `an envelope reaching past the scope is refused`() {
    val past = AudioLevel.Envelope(listOf(EnvelopePoint(9_000.milliseconds, 1f)))
    val verdict =
      fixture.plan(
        EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE).withAudio(past))))),
      )

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  @Test
  fun `an envelope asking for a negative gain is refused`() {
    val negative = AudioLevel.Envelope(listOf(EnvelopePoint(Duration.ZERO, -1f)))
    val verdict =
      fixture.plan(
        EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE).withAudio(negative))))),
      )

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  // A constant level is held to the rule an envelope point is, at every scope.
  @Test
  fun `a negative or non-finite volume is refused at every scope`() {
    for (gain in listOf(-0.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
      val clip = composition(fixture.clip(audioRate = AUDIO_RATE).withAudio(AudioLevel.Volume(gain)))
      val track =
        EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), audio = AudioLevel.Volume(gain))))
      val whole = composition(fixture.clip(audioRate = AUDIO_RATE)).withAudio(AudioSpec.Volume(gain))

      for (edit in listOf(clip, track, whole)) {
        val error =
          withClue("gain $gain") {
            fixture.plan(edit).shouldBeInstanceOf<Verdict.Incapable>()
          }.reasons.single()
        error.shouldBeInstanceOf<FilmstripError.InvalidComposition>()
      }
    }
  }

  // Levels multiply and are not capped, so a boost plans at every scope like a cut does.
  @Test
  fun `a volume above one plans at every scope`() {
    val clip = composition(fixture.clip(audioRate = AUDIO_RATE).withAudio(AudioLevel.Volume(1.5f)))
    val track =
      EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), audio = AudioLevel.Volume(1.5f))))
    val whole = composition(fixture.clip(audioRate = AUDIO_RATE)).withAudio(AudioSpec.Volume(1.5f))

    for (edit in listOf(clip, track, whole)) fixture.plan(edit).shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun `a speed other than one keeps a clip off the copy path`() {
    val fast = fixture.clip(audioRate = AUDIO_RATE).withSpeed(1.5)
    val verdict = fixture.plan(composition(fast))

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.copyBlockedBy shouldBe listOf(CopyBlocker.ClipSpeedChanged)
    fixture.resolve(composition(fast)).path shouldBe ExportPath.Transcode
    fixture.resolve(composition(fast.withSpeed(1.0))).path shouldBe ExportPath.Copy
  }

  @Test
  fun `an envelope keeps a clip off the copy path`() {
    val faded = fixture.clip(audioRate = AUDIO_RATE).withAudio(fadeIn(1_000.milliseconds))
    val verdict = fixture.plan(composition(faded))

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.copyBlockedBy shouldBe listOf(CopyBlocker.ClipGainChanged)
    fixture.resolve(composition(faded)).path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a fade keeps a clip off the copy path even where the level is untouched`() {
    val faded = fixture.clip(audioRate = AUDIO_RATE, fadeIn = 1_000.milliseconds)
    val verdict = fixture.plan(composition(faded))

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.copyBlockedBy shouldBe listOf(CopyBlocker.ClipGainChanged)
    fixture.resolve(composition(faded)).path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a track fade keeps an export off the copy path`() {
    val composition =
      EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), fadeOut = 1_000.milliseconds)))
    val verdict = fixture.plan(composition)

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.copyBlockedBy shouldBe listOf(CopyBlocker.TrackGainChanged)
    fixture.resolve(composition).path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a long envelope crossed with a slow fade does not explode into segments`() {
    // The fold subdivides only where both sides bow, and a slow track fade barely moves across one
    // step of a detailed clip envelope. Without that, every step would split and hand the backends
    // a segment list an ffmpeg expression cannot even parse.
    val detailed =
      AudioLevel.Envelope(
        (0..60).map { index -> EnvelopePoint((index * 100).milliseconds, if (index % 2 == 0) 1f else 0.4f) },
      )
    val composition =
      EditComposition(
        listOf(
          Track(clips = listOf(fixture.clip(audioRate = AUDIO_RATE).withAudio(detailed)), fadeIn = 6_000.milliseconds),
        ),
      )

    val gain = firstClipOf(fixture.resolve(composition)).gain
    // Sixty steps that each split to the cap would be nearly a thousand segments. The fade moves a
    // sixtieth of its range across one step, so each needs two chords and not sixteen.
    val ifEveryStepSplitToTheCap = 60 * ResolvedGain.MAX_PRODUCT_CHORDS
    withClue("the fold produced ${gain.segments.size} segments against a $ifEveryStepSplitToTheCap worst case") {
      (gain.segments.size < ifEveryStepSplitToTheCap / 4).shouldBeTrue()
    }
    // Still accurate where it matters, sampled off a breakpoint and away from either end.
    val expected = detailedGainAt(2_950.milliseconds) * (2_950f / 6_000f)
    gain.gainAt(2_950.milliseconds) shouldBe (expected plusOrMinus 0.01f)
  }

  // The detailed envelope above, read the way the model says: linear between neighbouring points.
  private fun detailedGainAt(time: Duration): Float {
    val step = (time.inWholeMilliseconds / 100).toInt()
    val within = (time.inWholeMilliseconds % 100) / 100f
    val left = if (step % 2 == 0) 1f else 0.4f
    val right = if ((step + 1) % 2 == 0) 1f else 0.4f
    return left + (right - left) * within
  }

  private fun fadeIn(duration: Duration) =
    AudioLevel.Envelope(listOf(EnvelopePoint(Duration.ZERO, 0f), EnvelopePoint(duration, 1f)))

  private fun fadeOut(duration: Duration) =
    AudioLevel.Envelope(
      listOf(
        EnvelopePoint(duration, 1f, EnvelopeAnchor.End),
        EnvelopePoint(Duration.ZERO, 0f, EnvelopeAnchor.End),
      ),
    )
}

// Every clip carries audio, since a fade over a clip that has none is left out of the plan.
private const val AUDIO_RATE = 48_000
