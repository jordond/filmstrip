package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.audioCodecOf
import dev.jordond.filmstrip.media.clipAudioTrackOf
import dev.jordond.filmstrip.media.defaultAudioTrackOf
import dev.jordond.filmstrip.transform.internal.copy.Mp4Copy
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The planner settles which audio track each clip plays, once, so every backend reads the same one.
 */
class AudioTrackPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun eachTrackAClipNamesIsTheOneItsResolvedClipCarries() {
    val clip = fixture.multiAudioClip()
    val tracks = fixture.infos(composition(clip)).getValue(clip.source).audioTracks

    tracks.forEachIndexed { ordinal, track ->
      val chosen = clip.withAudioTrack(track.id)

      val resolved =
        fixture
          .resolve(composition(chosen))
          .tracks
          .single()
          .clips
          .single()

      val audio = resolved.audioTrack.shouldNotBeNull()
      audio.id shouldBe track.id
      audio.ordinal shouldBe ordinal
      audio.track shouldBe clipAudioTrackOf(chosen, resolved.info)
    }
  }

  @Test
  fun aClipThatNamesNoTrackCarriesTheSourcesDefault() {
    val clip = fixture.multiAudioClip()
    val info = fixture.infos(composition(clip)).getValue(clip.source)

    val audio =
      fixture
        .resolve(composition(clip))
        .tracks
        .single()
        .clips
        .single()
        .audioTrack
        .shouldNotBeNull()

    audio.track shouldBe defaultAudioTrackOf(info.audioTracks)
    audio.ordinal shouldBe info.audioTracks.indexOf(defaultAudioTrackOf(info.audioTracks))
  }

  @Test
  fun aSourceWithNoAudioResolvesNoTrack() {
    fixture
      .resolve(composition(fixture.clip()))
      .tracks
      .single()
      .clips
      .single()
      .audioTrack
      .shouldBeNull()
  }

  // The default track sits in the middle, so a check that reads it instead of the chosen one answers the other way.
  @Test
  fun aCopyIsBlockedByTheCodecOfTheTrackTheClipPlaysNotTheDefault() {
    val clip = fixture.multiAudioClip(codecs = listOf("ac-3", "mp4a", "mp4a"))
    val chosen = clip.withAudioTrack(firstTrackOf(clip))

    val plan =
      fixture
        .plan(composition(chosen), muxerAccepts = { Mp4Copy.accepts(it) })
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan

    plan.path shouldBe ExportPath.Transcode
    plan.copyBlockedBy shouldContain CopyBlocker.MuxerRefusesSource
    plan.copyBlockedBy shouldContain CopyBlocker.SourceCodecUnnameable
  }

  @Test
  fun aCopyOfAChosenTrackTheMuxerTakesWritesThatTracksFormat() {
    val clip = fixture.multiAudioClip(codecs = listOf("mp4a", "ac-3", "mp4a"), rates = listOf(48_000, 48_000, 44_100))
    val chosen = clip.withAudioTrack(lastTrackOf(clip))
    val track = clipAudioTrackOf(chosen, fixture.infos(composition(chosen)).getValue(chosen.source)).shouldNotBeNull()

    val plan =
      fixture
        .plan(composition(chosen), muxerAccepts = { Mp4Copy.accepts(it) })
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan

    plan.path shouldBe ExportPath.Copy
    plan.output.audioCodec shouldBe audioCodecOf(track.codec.kind)
    plan.output.audioFormat shouldBe AudioFormat(track.sampleRate, track.channelCount)
  }

  // Forced off the copy path, since a copy keeps the rate of the track it writes without asking an encoder.
  @Test
  fun aTranscodeEncodesAtTheRateOfTheTrackTheClipPlays() {
    val clip = fixture.multiAudioClip(rates = listOf(44_100, 48_000, 48_000))
    val chosen = clip.withAudioTrack(firstTrackOf(clip))
    val track = clipAudioTrackOf(chosen, fixture.infos(composition(chosen)).getValue(chosen.source)).shouldNotBeNull()
    val device = fixture.device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 2)))

    val plan =
      fixture
        .plan(composition(chosen), device = device, muxerAccepts = { false })
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan

    plan.output.audioFormat
      .shouldNotBeNull()
      .sampleRate shouldBe track.sampleRate
  }

  @Test
  fun aClipWithAnInertEffectKeepsTheTrackItNames() {
    val chosen = middleTrackClip().withEffects(listOf(Brightness(1f)))

    assertPlaysTheTrackItNames(composition(chosen), chosen)
  }

  @Test
  fun anEditWithAnInertFadeKeepsTheTrackItsClipNames() {
    val chosen = middleTrackClip()
    val silent = fixture.clip(fadeOut = 1.seconds)

    assertPlaysTheTrackItNames(composition(chosen, silent), chosen)
  }

  @Test
  fun anEditWithAnInertFillKeepsTheTrackItsClipNames() {
    val chosen = middleTrackClip()

    assertPlaysTheTrackItNames(composition(chosen).withFill(Fill.Blurred()), chosen)
  }

  // The default sits first and the chosen track in the middle at its own rate, so falling back to the default reads a
  // different track and a different rate.
  private fun middleTrackClip(): Clip {
    val clip = fixture.multiAudioClip(defaults = listOf(true, false, false), rates = listOf(48_000, 44_100, 48_000))
    val middle =
      fixture
        .infos(composition(clip))
        .getValue(clip.source)
        .audioTracks[1]
        .id
    return clip.withAudioTrack(middle)
  }

  // Forced off the copy path, so the output's rate is the one the chosen track is encoded at.
  private fun assertPlaysTheTrackItNames(
    edit: Composition,
    chosen: Clip,
  ) {
    val track = clipAudioTrackOf(chosen, fixture.infos(edit).getValue(chosen.source)).shouldNotBeNull()
    val device = fixture.device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 2)))

    val plan =
      fixture
        .plan(edit, device = device, muxerAccepts = { false })
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan
    val resolved = fixture.resolve(edit, device = device, muxerAccepts = { false })

    plan.inertNotices.shouldNotBeEmpty()
    resolved.tracks
      .first()
      .clips
      .first()
      .audioTrack
      .shouldNotBeNull()
      .id shouldBe track.id
    plan.output.audioFormat
      .shouldNotBeNull()
      .sampleRate shouldBe track.sampleRate
  }

  private fun firstTrackOf(clip: Clip): AudioTrackId =
    fixture
      .infos(composition(clip))
      .getValue(clip.source)
      .audioTracks
      .first()
      .id

  private fun lastTrackOf(clip: Clip): AudioTrackId =
    fixture
      .infos(composition(clip))
      .getValue(clip.source)
      .audioTracks
      .last()
      .id
}
