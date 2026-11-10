package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.edit.Clip
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The one rule every backend plays a source's audio by, when a clip names a track and when it names none.
 */
class AudioTracksTest {
  private val first = track(id = 2, isDefault = false)
  private val middle = track(id = 3, isDefault = true)
  private val last = track(id = 4, isDefault = false)

  @Test
  fun theFlaggedTrackWinsOverTheFirst() {
    defaultAudioTrackOf(listOf(first, middle, last)) shouldBeSameInstanceAs middle
  }

  @Test
  fun theFirstFlaggedTrackWinsWhenSeveralAreFlagged() {
    val alsoFlagged = track(id = 5, isDefault = true)

    defaultAudioTrackOf(listOf(first, middle, alsoFlagged)) shouldBeSameInstanceAs middle
  }

  @Test
  fun theFirstTrackPlaysWhenNoneIsFlagged() {
    val unflagged = track(id = 3, isDefault = false)

    defaultAudioTrackOf(listOf(first, unflagged, last)) shouldBeSameInstanceAs first
  }

  @Test
  fun aSourceWithNoAudioHasNoDefault() {
    defaultAudioTrackOf(emptyList()).shouldBeNull()
  }

  @Test
  fun mediaInfoReportsTheDefaultTrackAsItsAudio() {
    info(first, middle, last).audio shouldBeSameInstanceAs defaultAudioTrackOf(listOf(first, middle, last))
  }

  @Test
  fun aClipPlaysEachTrackItNames() {
    val info = info(first, middle, last)

    listOf(first, middle, last).forEach { track ->
      clipAudioTrackOf(Clip(SOURCE, audioTrack = track.id), info) shouldBeSameInstanceAs track
    }
  }

  @Test
  fun aClipThatNamesNoTrackPlaysTheDefault() {
    val info = info(first, middle, last)

    clipAudioTrackOf(Clip(SOURCE), info) shouldBeSameInstanceAs defaultAudioTrackOf(info.audioTracks)
  }

  @Test
  fun aClipNamingATrackTheSourceLacksPlaysNothing() {
    clipAudioTrackOf(Clip(SOURCE, audioTrack = AudioTrackId(9)), info(first, middle, last)).shouldBeNull()
  }

  @Test
  fun anUndeterminedOrBlankLanguageReadsAsNone() {
    audioLanguageOf("und").shouldBeNull()
    audioLanguageOf(" ").shouldBeNull()
    audioLanguageOf(null).shouldBeNull()
    audioLanguageOf("fra") shouldBe "fra"
  }

  @Test
  fun aMovieTrackIsNamedByItsTrackId() {
    audioTrackIdOf(isMovie = true, trackId = 7, ordinal = 1) shouldBe AudioTrackId(7)
  }

  // A transport stream's packet id is a number no other backend reads, so it never names a track.
  @Test
  fun aTrackOfAnyOtherContainerIsNamedByItsPlaceFromOne() {
    audioTrackIdOf(isMovie = false, trackId = 257, ordinal = 1) shouldBe AudioTrackId(2)
    audioTrackIdOf(isMovie = false, trackId = null, ordinal = 1) shouldBe AudioTrackId(2)
  }

  // A track id of zero is reserved in an MP4, so a file that writes one has numbered nothing.
  @Test
  fun aMovieTrackWithNoUsableIdIsNamedByItsPlaceFromOne() {
    audioTrackIdOf(isMovie = true, trackId = null, ordinal = 1) shouldBe AudioTrackId(2)
    audioTrackIdOf(isMovie = true, trackId = 0, ordinal = 1) shouldBe AudioTrackId(2)
    audioTrackIdOf(isMovie = true, trackId = -3, ordinal = 2) shouldBe AudioTrackId(3)
  }

  private fun info(vararg tracks: AudioTrackInfo) =
    MediaInfo(duration = 2.seconds, video = null, audioTracks = tracks.toList(), isExportable = true)

  private fun track(
    id: Long,
    isDefault: Boolean,
  ) = AudioTrackInfo(
    id = AudioTrackId(id),
    codec = TrackCodec("mp4a", CodecKind.Aac),
    sampleRate = 48_000,
    channelCount = 2,
    bitrate = null,
    language = null,
    isDefault = isDefault,
  )

  private companion object {
    val SOURCE = MediaSource.Path("/three_audio.mp4")
  }
}
