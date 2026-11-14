package dev.jordond.filmstrip.audio

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class AudioEffectModelTest {
  private val source = MediaSource.of("/fixtures/clip.mp4")

  @Test
  fun `an edit rebuilt with equal audio effects is the same edit`() {
    edit(clip = listOf(Hum(0.2f))) shouldBe edit(clip = listOf(Hum(0.2f)))
    edit(clip = listOf(Hum(0.2f))) shouldNotBe edit(clip = listOf(Hum(0.3f)))
    diff(edit(mix = listOf(Hum(0.2f))), edit(mix = listOf(Hum(0.2f)))) shouldBe CompositionDiff.Equal
  }

  @Test
  fun `a changed audio effect at any scope is a parameter change`() {
    val base = edit(clip = listOf(Hum(0.2f)), track = listOf(Hum(0.4f)), mix = listOf(Hum(0.6f)))

    diff(base, edit(clip = listOf(Hum(0.3f)), track = base.tracks[0].audioEffects, mix = base.audioEffects)) shouldBe
      CompositionDiff.ParametersOnly
    diff(base, edit(clip = listOf(Hum(0.2f)), track = listOf(Hum(0.5f)), mix = base.audioEffects)) shouldBe
      CompositionDiff.ParametersOnly
    diff(base, edit(clip = listOf(Hum(0.2f)), track = listOf(Hum(0.4f)), mix = emptyList())) shouldBe
      CompositionDiff.ParametersOnly
  }

  @Test
  fun `audio effects in a different order are a parameter change`() {
    val previous = edit(clip = listOf(Hum(0.2f), Hum(0.7f)))
    val next = edit(clip = listOf(Hum(0.7f), Hum(0.2f)))

    diff(previous, next) shouldBe CompositionDiff.ParametersOnly
  }

  @Test
  fun `an audio effect never changes the frame revision`() {
    val plain = edit()
    val effected = edit(clip = listOf(Hum(0.2f)), track = listOf(Hum(0.4f)), mix = listOf(Hum(0.6f)))

    effected.effectsRevision() shouldBe plain.effectsRevision()
  }

  @Test
  fun `the builders declare audio effects at each scope in the order written`() {
    val composition =
      compositionOf {
        clip(source) {
          audioEffects {
            add(Hum(0.1f))
            addAll(listOf(Hum(0.2f)))
          }
          addAudioEffects(listOf(Hum(0.3f)))
        }
        track(TrackContent.Audio) {
          clip(source)
          audioEffects { add(Hum(0.4f)) }
          addAudioEffects(listOf(Hum(0.5f)))
        }
        audioEffects { add(Hum(0.6f)) }
        addAudioEffects(listOf(Hum(0.7f)))
      }

    composition.clips[0].audioEffects shouldContainExactly listOf(Hum(0.1f), Hum(0.2f), Hum(0.3f))
    composition.tracks[0].audioEffects.shouldBeEmpty()
    composition.tracks[1].audioEffects shouldContainExactly listOf(Hum(0.4f), Hum(0.5f))
    composition.tracks[1]
      .clips[0]
      .audioEffects
      .shouldBeEmpty()
    composition.audioEffects shouldContainExactly listOf(Hum(0.6f), Hum(0.7f))
  }

  @Test
  fun `every copy keeps the audio effects it was not asked to change`() {
    val hum = listOf(Hum(0.2f))
    val clip = Clip(source, TimeRange(0.seconds, 2.seconds), audioEffects = hum)
    val track = Track(listOf(clip), audioEffects = hum)
    val composition = Composition(listOf(track), audioEffects = hum)

    listOf(
      clip.withTrim(null),
      clip.withEffects(emptyList()),
      clip.withAudio(clip.audio),
      clip.withSnapWithin(1.seconds),
      clip.withFadeIn(1.seconds),
      clip.withFadeOut(1.seconds),
      clip.withSpeed(2.0),
      clip.withAudioTrack(null),
    ).forEach { it.audioEffects shouldBe hum }
    listOf(
      track.withClips(emptyList()),
      track.withContent(TrackContent.Audio),
      track.withEffects(emptyList()),
      track.withAudio(track.audio),
      track.withStart(1.seconds),
      track.withLooping(true),
      track.withFadeIn(1.seconds),
      track.withFadeOut(1.seconds),
    ).forEach { it.audioEffects shouldBe hum }
    listOf(
      composition.withClips(listOf(clip)),
      composition.withTracks(listOf(track)),
      composition.withEffects(emptyList()),
      composition.withAudio(composition.audio),
      composition.withFill(composition.fill),
    ).forEach { it.audioEffects shouldBe hum }
    composition.withClips(listOf(clip)).tracks[0].audioEffects shouldBe hum

    clip.withAudioEffects(emptyList()) shouldBe Clip(source, clip.trim)
    track.withAudioEffects(emptyList()) shouldBe Track(listOf(clip))
    composition.withAudioEffects(emptyList()) shouldBe Composition(listOf(track))
  }

  private fun edit(
    clip: List<AudioEffectSpec> = emptyList(),
    track: List<AudioEffectSpec> = emptyList(),
    mix: List<AudioEffectSpec> = emptyList(),
  ): Composition =
    Composition(
      tracks = listOf(Track(listOf(Clip(source, audioEffects = clip)), audioEffects = track)),
      audioEffects = mix,
    )

  private data class Hum(
    val level: Float,
  ) : AudioEffectSpec {
    override val id: EffectId = EffectId("test.hum")
  }
}
