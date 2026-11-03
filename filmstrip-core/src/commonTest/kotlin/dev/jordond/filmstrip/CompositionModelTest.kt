package dev.jordond.filmstrip

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.EnvelopePoint
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The composition model: one primary track most of the time, several when an edit layers.
 *
 * No `@OptIn` here: every filmstrip module opts in to its own experimental marker.
 */
class CompositionModelTest {
  @Test
  fun clipsWrittenWithoutATrackLandOnThePrimaryOne() {
    val composition =
      compositionOf {
        clip(source("a")) { trim(0.milliseconds, 1_000.milliseconds) }
        clip(source("b")) { trim(0.milliseconds, 2_000.milliseconds) }
      }

    composition.tracks.size shouldBe 1
    composition.clips.size shouldBe 2
    composition.tracks.first().content shouldBe TrackContent.AudioAndVideo
  }

  @Test
  fun anEditWrittenEntirelyAsTracksGetsNoEmptyLeadingOne() {
    // An empty primary track would hand the output frame to nothing.
    val composition =
      compositionOf {
        track(TrackContent.Video) { clip(source("pip")) { trim(0.milliseconds, 1_000.milliseconds) } }
      }

    composition.tracks.size shouldBe 1
    composition.tracks.first().content shouldBe TrackContent.Video
  }

  @Test
  fun theDurationIsTheLongestNonLoopingTrack() {
    // Tracks play together, so the composition is as long as the longest of them, not their sum.
    val composition =
      compositionOf {
        clip(source("main")) { trim(0.milliseconds, 5_000.milliseconds) }
        track(TrackContent.Video) { clip(source("pip")) { trim(0.milliseconds, 9_000.milliseconds) } }
      }

    composition.duration shouldBe 9_000.milliseconds
  }

  @Test
  fun aLoopingTrackNeverDecidesTheDuration() {
    val composition =
      compositionOf {
        clip(source("main")) { trim(0.milliseconds, 5_000.milliseconds) }
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 180_000.milliseconds) }
          looping()
        }
      }

    composition.duration shouldBe 5_000.milliseconds
  }

  @Test
  fun anEditWhereEveryTrackLoopsHasNothingToBoundIt() {
    val composition =
      compositionOf {
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 180_000.milliseconds) }
          looping()
        }
      }

    composition.duration.shouldBeNull()
  }

  @Test
  fun anOpenEndedTrimLeavesTheDurationUnknown() {
    val composition = compositionOf { clip(source("a")) }

    composition.duration.shouldBeNull()
  }

  @Test
  fun aTrackStartOffsetPushesItsEnd() {
    val composition =
      compositionOf {
        clip(source("main")) { trim(0.milliseconds, 5_000.milliseconds) }
        track(TrackContent.Audio) {
          clip(source("sting")) { trim(0.milliseconds, 2_000.milliseconds) }
          startAt(4_000.milliseconds)
        }
      }

    composition.duration shouldBe 6_000.milliseconds
  }

  @Test
  fun audioLevelsAreDeclaredAtEveryScopeTheyBelongTo() {
    val composition =
      compositionOf {
        clip(source("dialogue")) {
          trim(0.milliseconds, 5_000.milliseconds)
          audio(AudioLevel.Mute)
        }
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 5_000.milliseconds) }
          audio(AudioLevel.Volume(0.3f))
        }
      }

    composition.clips.first().audio shouldBe AudioLevel.Mute
    composition.tracks[1].audio shouldBe AudioLevel.Volume(0.3f)
    composition.tracks.first().audio shouldBe AudioLevel.Inherit
  }

  @Test
  fun aFadeIsStoredApartFromTheLevelItRamps() {
    val composition =
      compositionOf {
        clip(source("dialogue")) { trim(0.milliseconds, 5_000.milliseconds) }
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 5_000.milliseconds) }
          audio(AudioLevel.Volume(0.3f))
          fadeIn(1_000.milliseconds)
          fadeOut(2_000.milliseconds)
        }
      }

    val track = composition.tracks[1]
    track.audio shouldBe AudioLevel.Volume(0.3f)
    track.fadeIn shouldBe 1_000.milliseconds
    track.fadeOut shouldBe 2_000.milliseconds
  }

  @Test
  fun whereAFadeIsWrittenAgainstTheVolumeMakesNoDifference() {
    val before =
      compositionOf {
        clip(source("a")) {
          fadeIn(1_000.milliseconds)
          audio(AudioLevel.Volume(0.5f))
        }
      }
    val after =
      compositionOf {
        clip(source("a")) {
          audio(AudioLevel.Volume(0.5f))
          fadeIn(1_000.milliseconds)
        }
      }

    after.clips.first() shouldBe before.clips.first()
  }

  @Test
  fun aFadeLeavesAHandWrittenEnvelopeAlone() {
    // The fade is its own curve the plan multiplies in, so nothing is appended to the points here
    // and the envelope still reads in the order it was written.
    val envelope =
      AudioLevel.Envelope(listOf(EnvelopePoint(0.milliseconds, 1f), EnvelopePoint(2_000.milliseconds, 0.5f)))
    val composition =
      compositionOf {
        clip(source("a")) {
          trim(0.milliseconds, 2_000.milliseconds)
          audio(envelope)
          fadeIn(1_000.milliseconds)
        }
      }

    val clip = composition.clips.first()
    clip.audio shouldBe envelope
    clip.fadeIn shouldBe 1_000.milliseconds
  }

  @Test
  fun aLoopingTrackStillCarriesItsFadeOut() {
    // Whether a loop can anchor a fade out is the plan's call, so the builder passes it through.
    val composition =
      compositionOf {
        clip(source("a")) { trim(0.milliseconds, 5_000.milliseconds) }
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 1_000.milliseconds) }
          fadeIn(500.milliseconds)
          fadeOut(500.milliseconds)
          looping()
        }
      }

    val track = composition.tracks[1]
    track.fadeIn shouldBe 500.milliseconds
    track.fadeOut shouldBe 500.milliseconds
  }

  @Test
  fun withClipsReplacesThePrimaryTrackAndLeavesTheRestAlone() {
    val composition =
      compositionOf {
        clip(source("a")) { trim(0.milliseconds, 1_000.milliseconds) }
        track(TrackContent.Audio) { clip(source("music")) { trim(0.milliseconds, 1_000.milliseconds) } }
      }

    val replaced = composition.withClips(listOf(composition.clips.first().withTrim(null)))

    replaced.tracks.size shouldBe 2
    replaced.clips
      .first()
      .trim
      .shouldBeNull()
    replaced.tracks[1] shouldBe composition.tracks[1]
  }

  @Test
  fun withClipsWithTracksWithEffectsAndWithAudioAllPreserveTheFill() {
    val fill = Fill.Blurred(radius = 0.1f, dim = 0.5f)
    val composition =
      compositionOf {
        clip(source("main")) { trim(0.milliseconds, 1_000.milliseconds) }
        fill(fill)
      }

    composition.withClips(composition.clips).fill shouldBe fill
    composition.withTracks(composition.tracks).fill shouldBe fill
    composition.withEffects(composition.effects).fill shouldBe fill
    composition.withAudio(composition.audio).fill shouldBe fill
  }

  @Test
  fun fillSetOnTheBuilderReachesBuild() {
    val composition = compositionOf { fill(Fill.White) }

    composition.fill shouldBe Fill.White
  }

  private fun source(name: String): MediaSource = MediaSource.of("/fixtures/$name.mp4")
}
