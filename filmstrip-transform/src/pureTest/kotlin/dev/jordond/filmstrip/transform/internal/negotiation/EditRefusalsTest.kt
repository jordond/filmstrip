package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.outputHeightRefusal
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import dev.jordond.filmstrip.transform.internal.refusal
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Which streams an output keeps, and the refusals that read only the shape of an edit and what its sources are.
 */
class EditRefusalsTest {
  private val fixture = PlannerFixture()

  @Test
  fun `an edit with audio under it keeps both streams`() {
    val edit = composition(fixture.clip(audioRate = 48_000))

    streamsOf(edit, ExportSpec(), fixture.infos(edit)).keeps() shouldBe (true to true)
  }

  @Test
  fun `audio goes when the edit removes it the spec drops it or no source carries any`() {
    val withAudio = fixture.clip(audioRate = 48_000)
    val removed = Composition(listOf(Track(listOf(withAudio))), audio = AudioSpec.Remove)
    val silent = composition(fixture.clip())

    streamsOf(removed, ExportSpec(), fixture.infos(removed)).keeps() shouldBe (true to false)
    val dropped = composition(withAudio)
    streamsOf(dropped, ExportSpec(audioCodec = AudioCodec.None), fixture.infos(dropped)).keeps() shouldBe
      (true to false)
    streamsOf(silent, ExportSpec(), fixture.infos(silent)).keeps() shouldBe (true to false)
  }

  @Test
  fun `an audio only edit drops the video and keeps the audio`() {
    val edit = Composition(listOf(Track(listOf(fixture.clip(audioRate = 48_000)))), audio = AudioSpec.AudioOnly)

    streamsOf(edit, ExportSpec(), fixture.infos(edit)).keeps() shouldBe (false to true)
  }

  @Test
  fun `a well formed edit is not refused`() {
    val edit =
      Composition(
        listOf(
          Track(listOf(fixture.clip(effects = listOf(PUSH_IN)))),
          Track(listOf(fixture.audioOnlyClip()), content = TrackContent.Audio, looping = true),
        ),
      )

    refusal(edit).shouldBeNull()
  }

  @Test
  fun `the shape of the tracks is refused in the order the planner reads it`() {
    message(Composition(emptyList())) shouldBe "A composition needs at least one track."
    message(Composition(listOf(Track(listOf(fixture.clip())), Track(listOf(fixture.clip()))))) shouldBe
      "This backend renders video from the primary track only. A second video track needs a compositor, which has " +
      "not landed here. A second audio-only track works."
    message(Composition(listOf(Track(emptyList())))) shouldBe "The primary track has no clips."
    message(Composition(listOf(Track(listOf(fixture.clip()), content = TrackContent.Audio)))) shouldBe
      NO_PRIMARY_VIDEO
    message(Composition(listOf(Track(listOf(fixture.clip()), looping = true)))) shouldBe
      "Every track loops, so the composition has nothing to bound it."
    message(Composition(listOf(Track(listOf(fixture.clip()), start = (-1).milliseconds)))) shouldBe
      "A track's start is negative, so it would begin before the composition does. A start is measured from the " +
      "start of the composition and has to be zero or later."
  }

  @Test
  fun `a frame rate of zero or less is refused with the rate it asked for`() {
    val edit = composition(fixture.clip())

    for (rate in listOf(0, -24)) {
      message(edit, ExportSpec(frameRate = rate)) shouldBe
        "The export asks for $rate fps, and a frame rate has to be positive. Leave it unset to keep the source's rate."
    }
  }

  @Test
  fun `a height of zero or less is refused the way every output height is`() {
    val edit = composition(fixture.clip())

    for (height in listOf(0, -720)) {
      message(edit, ExportSpec(heightPx = height)) shouldBe outputHeightRefusal(height)?.message
    }
  }

  @Test
  fun `an edit whose every track is cancelled out has nothing to encode`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip()), content = TrackContent.Video)), audio = AudioSpec.AudioOnly)

    message(edit) shouldBe
      "No track survives what the composition and the spec ask for, so the export would write a file with no " +
      "tracks in it."
  }

  @Test
  fun `a source the planner has no answer for is unreadable by name`() {
    val clip = fixture.clip()
    val edit = composition(clip)

    val error = refusal(edit, infos = emptyMap()).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

    error.source shouldBe clip.source.describe()
    error.message shouldBe "The source could not be read, so there is nothing to plan against."
  }

  @Test
  fun `a protected source is refused by name`() {
    val clip = fixture.clip(exportable = false)

    refusal(composition(clip)).shouldBeInstanceOf<FilmstripError.SourceNotExportable>().message shouldBe
      "${clip.source.describe()} is protected and cannot be exported."
  }

  @Test
  fun `a clip only effect off a clip is refused by id`() {
    val onTrack = Composition(listOf(Track(listOf(fixture.clip()), effects = listOf(PUSH_IN))))
    val onComposition = Composition(listOf(Track(listOf(fixture.clip()))), effects = listOf(PUSH_IN))

    for (edit in listOf(onTrack, onComposition)) {
      val error = refusal(edit).shouldBeInstanceOf<FilmstripError.UnsupportedEffect>()
      error.specId shouldBe EffectIds.KEN_BURNS
      error.message shouldBe
        "This effect reads where a frame sits inside the run it is drawn over, so it belongs on a clip. Declared on " +
        "a track or on the composition it would draw one run on one engine and a run per clip on another. Move it " +
        "onto the clips it should travel across."
    }
  }

  @Test
  fun `a clip only effect on a looping track's clip is refused by id`() {
    val edit =
      Composition(
        listOf(
          Track(listOf(fixture.clip())),
          Track(listOf(fixture.clip(effects = listOf(PUSH_IN))), content = TrackContent.Audio, looping = true),
        ),
      )

    val error = refusal(edit).shouldBeInstanceOf<FilmstripError.UnsupportedEffect>()

    error.specId shouldBe EffectIds.KEN_BURNS
    error.message shouldBe
      "This effect reads where a frame sits inside the run it is drawn over, and a looping track lays its clips " +
      "down more than once, so there is no single run to measure against. Take the loop off the track, or take " +
      "the effect off the clip."
  }

  // An effect any scope may hold stays legal on a looping track, so only the clip-only one is refused there.
  @Test
  fun `an effect any scope may hold is not refused on a looping track`() {
    val edit =
      Composition(
        listOf(
          Track(listOf(fixture.clip())),
          Track(listOf(fixture.clip(effects = listOf(Brightness(2f)))), content = TrackContent.Audio, looping = true),
        ),
      )

    refusal(edit).shouldBeNull()
  }

  @Test
  fun `the first refusal wins when an edit breaks several rules`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(exportable = false)), start = (-1).milliseconds)))

    message(edit, ExportSpec(frameRate = 0)) shouldBe
      "A track's start is negative, so it would begin before the composition does. A start is measured from the " +
      "start of the composition and has to be zero or later."
  }

  @Test
  fun `a clip speed outside the range is refused by name`() {
    listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, CLIP_SPEED_RANGE.endInclusive * 2).forEach { speed ->
      val edit = composition(fixture.clip().withSpeed(speed))

      val error = structuralRefusal(edit, ExportSpec(), fixture.infos(edit), Streams(true, false))

      error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldContain "speed of $speed"
    }
  }

  @Test
  fun `the ends of the clip speed range are not refused`() {
    listOf(CLIP_SPEED_RANGE.start, 0.7, CLIP_SPEED_RANGE.endInclusive).forEach { speed ->
      val edit = composition(fixture.clip().withSpeed(speed))

      structuralRefusal(edit, ExportSpec(), fixture.infos(edit), Streams(true, false)).shouldBeNull()
    }
  }

  @Test
  fun `a clip naming an audio track its source lacks is refused by name`() {
    val clip = fixture.multiAudioClip()
    val edit = composition(clip.withAudioTrack(AudioTrackId(9)))

    val message = message(edit)

    message shouldContain "asks for audio track 9"
    message shouldContain "2, 3, 4"
  }

  @Test
  fun `a clip naming any audio track its source carries is not refused`() {
    val clip = fixture.multiAudioClip()
    val edit = composition(clip)

    fixture.infos(edit).getValue(clip.source).audioTracks.forEach { track ->
      refusal(composition(clip.withAudioTrack(track.id))).shouldBeNull()
    }
  }

  private fun refusal(
    edit: Composition,
    spec: ExportSpec = ExportSpec(),
    infos: Map<MediaSource, MediaInfo> = fixture.infos(edit),
  ): FilmstripError? = structuralRefusal(edit, spec, infos, streamsOf(edit, spec, infos))

  private fun message(
    edit: Composition,
    spec: ExportSpec = ExportSpec(),
  ): String = refusal(edit, spec).shouldBeInstanceOf<FilmstripError.InvalidComposition>().message

  private fun Streams.keeps(): Pair<Boolean, Boolean> = keepVideo to keepAudio

  private companion object {
    val PUSH_IN = KenBurns(NormalizedRect.Full, NormalizedRect(0.15f, 0.15f, 0.85f, 0.85f), Easing.EaseInOut)
  }
}
