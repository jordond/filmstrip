package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.transform.internal.FakeResolver
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * How the planner settles the audio half of the output format.
 *
 * Covers removed and missing audio tracks, audio-only output and when it is refused, and how the sample rate and
 * channel count are snapped to what the encoder takes.
 */
class AudioFormatTest {
  private val fixture = PlannerFixture()

  @Test
  fun `removing audio writes no audio track and no audio format`() {
    val verdict = fixture.plan(composition(fixture.clip()).withAudio(AudioSpec.Remove))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.output.audioCodec shouldBe AudioCodec.None
    plan.output.audioFormat.shouldBeNull()
  }

  // A source with no audio track cannot back the track an encoder or a stream copy would be asked
  // to write, so the plan promises none rather than one a copy could never deliver and an encode
  // would have to invent from silence.
  @Test
  fun `a video-only source is not promised an audio track it has none of`() {
    val verdict = fixture.plan(composition(fixture.clip()))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.output.audioCodec shouldBe AudioCodec.None
    plan.output.audioFormat.shouldBeNull()
  }

  @Test
  fun `a clip with no audio next to one that has it still resolves an audio codec`() {
    val track = Track(listOf(fixture.clip(), fixture.clip(audioRate = 44_100)))
    val verdict = fixture.plan(EditComposition(listOf(track)))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.audioCodec shouldBe AudioCodec.Aac
  }

  @Test
  fun `audio-only output still plans and the video half of the format goes unwritten`() {
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 44_100)).withAudio(AudioSpec.AudioOnly))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.audioCodec shouldBe AudioCodec.Aac
  }

  @Test
  fun `an audio-only output over a track that carries no audio is refused`() {
    val track = Track(listOf(fixture.clip()), content = TrackContent.Video)
    val verdict = fixture.plan(EditComposition(listOf(track)).withAudio(AudioSpec.AudioOnly))

    verdict
      .shouldBeInstanceOf<Verdict.Incapable>()
      .reasons
      .single()
      .shouldBeInstanceOf<FilmstripError.InvalidComposition>()
  }

  @Test
  fun `an audio-only output over a source with no audio track is refused`() {
    val verdict = fixture.plan(composition(fixture.clip()).withAudio(AudioSpec.AudioOnly))

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  // A stream copy carries the source's own audio format across untouched, so this is a transcode
  // question and forced off the copy path to ask it.
  @Test
  fun `the source sample rate is kept when the encoder lists it`() {
    val device = fixture.device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 2)))
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 44_100)), device = device, muxerAccepts = { false })

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.audioFormat
      ?.sampleRate shouldBe 44_100
  }

  // An encoder handed a rate it cannot write resamples on its own, and the file then disagrees with
  // the plan a caller was shown. Forced off the copy path, since a copy never resamples at all.
  @Test
  fun `a sample rate the encoder refuses snaps to the nearest one it takes`() {
    val device = fixture.device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 2)))
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 37_800)), device = device, muxerAccepts = { false })

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.audioFormat
      ?.sampleRate shouldBe 44_100
  }

  // Android codecs routinely publish no rate list at all, and inventing a ceiling there would
  // resample a stream that was fine. Forced off the copy path, since a copy always keeps the
  // source's rate regardless of what an encoder would have done with it.
  @Test
  fun `an encoder that lists no rates is given the source's`() {
    val device = fixture.device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, emptyList(), 2)))
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 37_800)), device = device, muxerAccepts = { false })

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.audioFormat
      ?.sampleRate shouldBe 37_800
  }

  // Forced off the copy path, since a copy never remixes and would carry the source's real channel
  // count across regardless of what a mono-only encoder could take.
  @Test
  fun `a mono-only encoder is not asked for a stereo mix`() {
    val device = fixture.device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 1)))
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 48_000)), device = device, muxerAccepts = { false })

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.audioFormat
      ?.channelCount shouldBe 1
  }

  // A track effect lowers once per clip, so the same approximation is reached twice and a caller
  // would otherwise be told about it twice.
  @Test
  fun `an approximation reached on every clip is reported once`() {
    val track = Track(listOf(fixture.clip(), fixture.clip()), effects = listOf(Rotate(90)))
    val verdict =
      fixture.plan(EditComposition(listOf(track)), resolvers = listOf(FakeResolver(degrade = setOf(Rotate(90).id))))

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    degraded.adjustments.count { it.kind == AdjustmentKind.EffectApproximated } shouldBe 1
  }
}
