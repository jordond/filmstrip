package dev.jordond.filmstrip

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.CompositionBuilder
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
      composition {
        clip(source("a")) { trim(0.milliseconds, 1_000.milliseconds) }
        clip(source("b")) { trim(0.milliseconds, 2_000.milliseconds) }
      }

    assertEquals(1, composition.tracks.size)
    assertEquals(2, composition.clips.size)
    assertEquals(TrackContent.AudioAndVideo, composition.tracks.first().content)
  }

  @Test
  fun anEditWrittenEntirelyAsTracksGetsNoEmptyLeadingOne() {
    // An empty primary track would hand the output frame to nothing.
    val composition =
      composition {
        track(TrackContent.Video) { clip(source("pip")) { trim(0.milliseconds, 1_000.milliseconds) } }
      }

    assertEquals(1, composition.tracks.size)
    assertEquals(TrackContent.Video, composition.tracks.first().content)
  }

  @Test
  fun theDurationIsTheLongestNonLoopingTrack() {
    // Tracks play together, so the composition is as long as the longest of them, not their sum.
    val composition =
      composition {
        clip(source("main")) { trim(0.milliseconds, 5_000.milliseconds) }
        track(TrackContent.Video) { clip(source("pip")) { trim(0.milliseconds, 9_000.milliseconds) } }
      }

    assertEquals(9_000.milliseconds, composition.duration)
  }

  @Test
  fun aLoopingTrackNeverDecidesTheDuration() {
    val composition =
      composition {
        clip(source("main")) { trim(0.milliseconds, 5_000.milliseconds) }
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 180_000.milliseconds) }
          looping()
        }
      }

    assertEquals(5_000.milliseconds, composition.duration)
  }

  @Test
  fun anEditWhereEveryTrackLoopsHasNothingToBoundIt() {
    val composition =
      composition {
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 180_000.milliseconds) }
          looping()
        }
      }

    assertNull(composition.duration)
  }

  @Test
  fun anOpenEndedTrimLeavesTheDurationUnknown() {
    val composition = composition { clip(source("a")) }

    assertNull(composition.duration)
  }

  @Test
  fun aTrackStartOffsetPushesItsEnd() {
    val composition =
      composition {
        clip(source("main")) { trim(0.milliseconds, 5_000.milliseconds) }
        track(TrackContent.Audio) {
          clip(source("sting")) { trim(0.milliseconds, 2_000.milliseconds) }
          startAt(4_000.milliseconds)
        }
      }

    assertEquals(6_000.milliseconds, composition.duration)
  }

  @Test
  fun audioLevelsAreDeclaredAtEveryScopeTheyBelongTo() {
    val composition =
      composition {
        clip(source("dialogue")) {
          trim(0.milliseconds, 5_000.milliseconds)
          audio(AudioLevel.Mute)
        }
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 5_000.milliseconds) }
          audio(AudioLevel.Volume(0.3f))
        }
      }

    assertEquals(AudioLevel.Mute, composition.clips.first().audio)
    assertEquals(AudioLevel.Volume(0.3f), composition.tracks[1].audio)
    assertEquals(AudioLevel.Inherit, composition.tracks.first().audio)
  }

  @Test
  fun withClipsReplacesThePrimaryTrackAndLeavesTheRestAlone() {
    val composition =
      composition {
        clip(source("a")) { trim(0.milliseconds, 1_000.milliseconds) }
        track(TrackContent.Audio) { clip(source("music")) { trim(0.milliseconds, 1_000.milliseconds) } }
      }

    val replaced = composition.withClips(listOf(composition.clips.first().withTrim(null)))

    assertEquals(2, replaced.tracks.size)
    assertNull(replaced.clips.first().trim)
    assertEquals(composition.tracks[1], replaced.tracks[1])
  }

  @Test
  fun aLayeredEditListRoundTripsThroughJson() {
    val composition =
      composition {
        clip(source("main")) {
          trim(0.milliseconds, 5_000.milliseconds)
          audio(AudioLevel.Volume(0.8f))
        }
        track(TrackContent.Audio) {
          clip(source("music")) { trim(0.milliseconds, 5_000.milliseconds) }
          audio(AudioLevel.Volume(0.3f))
          startAt(1_000.milliseconds)
          looping()
        }
      }

    val json = Json.encodeToString(EditComposition.serializer(), composition)

    assertEquals(composition, Json.decodeFromString(EditComposition.serializer(), json))
    assertTrue(json.contains("\"volume\""), "audio levels are persisted by their stable names")
  }

  @Test
  fun aSolidFillRoundTripsThroughJson() {
    val composition = composition { fill(Fill.Solid(color = 0xFFFF0000.toInt())) }

    val json = Json.encodeToString(EditComposition.serializer(), composition)

    assertEquals(composition, Json.decodeFromString(EditComposition.serializer(), json))
  }

  @Test
  fun aBlurredFillRoundTripsThroughJson() {
    val composition = composition { fill(Fill.Blurred(radius = 0.1f, dim = 0.5f)) }

    val json = Json.encodeToString(EditComposition.serializer(), composition)

    assertEquals(composition, Json.decodeFromString(EditComposition.serializer(), json))
  }

  @Test
  fun withClipsWithTracksWithEffectsAndWithAudioAllPreserveTheFill() {
    val fill = Fill.Blurred(radius = 0.1f, dim = 0.5f)
    val composition =
      composition {
        clip(source("main")) { trim(0.milliseconds, 1_000.milliseconds) }
        fill(fill)
      }

    assertEquals(fill, composition.withClips(composition.clips).fill)
    assertEquals(fill, composition.withTracks(composition.tracks).fill)
    assertEquals(fill, composition.withEffects(composition.effects).fill)
    assertEquals(fill, composition.withAudio(composition.audio).fill)
  }

  @Test
  fun fillSetOnTheBuilderReachesBuild() {
    val composition = composition { fill(Fill.White) }

    assertEquals(Fill.White, composition.fill)
  }

  private fun composition(block: CompositionBuilder.() -> Unit): EditComposition =
    CompositionBuilder().apply(block).build()

  private fun source(name: String): MediaSource = MediaSource.of("/fixtures/$name.mp4")
}
