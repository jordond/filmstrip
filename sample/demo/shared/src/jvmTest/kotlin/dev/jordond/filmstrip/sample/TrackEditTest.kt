@file:OptIn(ExperimentalFilmstripApi::class, InternalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The extra tracks of the edit checked against what the library's track builder writes.
 */
class TrackEditTest {
  @Test
  fun `a looping audio track starting late at half volume builds the track the library writes`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds)
    val track =
      sequence.addTrack("music").apply {
        startSeconds = 1.5f
        looping = true
        gain = 0.5f
        fadeInSeconds = 0.5f
        fadeOutSeconds = 1.25f
      }
    sequence.addTo(track, ClipContent.Video(MUSIC), "music")

    EditState().composition(sequence).tracks shouldContainExactly
      compositionOf {
        clip(FIRST)
        track(TrackContent.Audio) {
          clip(MUSIC)
          startAt(1500.milliseconds)
          looping()
          audio(AudioLevel.Volume(0.5f))
          fadeIn(500.milliseconds)
          fadeOut(1250.milliseconds)
        }
      }.tracks
  }

  @Test
  fun `a looping track does not lengthen the edit and a timed one that runs past it does`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds)
    val alone = sequence.duration
    val track = sequence.addTrack("music").apply { startSeconds = 4.5f }
    sequence.addTo(track, ClipContent.Video(MUSIC), "music").probed(ProbeResult.Success(audio(8.seconds)))

    sequence.duration shouldBe
      compositionOf {
        clip(FIRST) { trim(0.seconds, 10.seconds) }
        track(TrackContent.Audio) {
          clip(MUSIC) { trim(0.seconds, 8.seconds) }
          startAt(4500.milliseconds)
        }
      }.duration
    sequence.duration shouldBe 12500.milliseconds

    track.looping = true
    sequence.duration shouldBe alone
  }

  @Test
  fun `a track with nothing readable on it is left out of the edit`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds)
    val track = sequence.addTrack("music")

    EditState().composition(sequence).tracks shouldHaveSize 1

    sequence.addTo(track, ClipContent.Video(MUSIC), "music").probed(ProbeResult.Failure(UNREADABLE))
    EditState().composition(sequence).tracks shouldHaveSize 1
  }

  @Test
  fun `removing a track and clearing the sequence drop its clips`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds)
    val track = sequence.addTrack("music")
    sequence.addTo(track, ClipContent.Video(MUSIC), "music")

    sequence.removeTrack(track.id)
    sequence.tracks.shouldBeEmpty()

    sequence.addTrack("again")
    sequence.clear()
    sequence.tracks.shouldBeEmpty()
  }

  @Test
  fun `a session probes the file it lays on a new track and forgets the track on request`() {
    val state =
      SampleAppState(
        filmstrip =
          Filmstrip {
            autoRegister(false)
            addMediaProberFactory {
              MediaProber {
                ProbeResult.Success(MediaInfo(8.seconds, SampleSessionTest.SOURCE_TRACK, emptyList(), true))
              }
            }
          },
        scope = CoroutineScope(Dispatchers.Unconfined),
      )
    state.addClip(FIRST, "first.mp4")

    state.addTrack(MUSIC, "music.m4a", looping = true)

    val track = state.sequence.tracks.single()
    track.looping shouldBe true
    track.clips.single().duration shouldBe 8.seconds
    state.sequence.clips shouldHaveSize 1
    state.composition()!!.tracks shouldHaveSize 2

    state.removeTrack(track.id)
    state.composition()!!.tracks shouldHaveSize 1
  }

  private companion object {
    val FIRST = MediaSource.of("/tmp/first.mp4")
    val MUSIC = MediaSource.of("/tmp/music.m4a")
    val UNREADABLE = FilmstripError.SourceUnreadable("music.m4a", "no")

    fun audio(duration: Duration): MediaInfo = MediaInfo(duration, null, emptyList(), true)
  }
}
