package dev.jordond.filmstrip.internal

import android.media.MediaFormat
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.TrackHeader
import dev.jordond.filmstrip.picture.bytes.fourcc
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How core names an MP4's audio tracks off the extractor's formats and the file's own track headers.
 *
 * Below API 28 the extractor reports no track id, so each format is matched to the `soun` header at its place among
 * them. A video header listed first must not shift that match.
 */
class AndroidAudioTracksTest {
  @Test
  fun formatsWithNoTrackIdTakeTheSoundHeaderAtTheirPlace() {
    val tracks = audioTracksOf(List(SOUND.size) { aac() }, MovieHeaders(duration = null, tracks = HEADERS))

    tracks.map { it.id } shouldBe SOUND.map { AudioTrackId(it.id) }
    tracks.map { it.isDefault } shouldBe SOUND.map { it.isEnabled }
    tracks.map { it.language } shouldBe SOUND.map { it.language }
  }

  @Test
  fun formatsWithATrackIdTakeTheSoundHeaderOfThatId() {
    val formats =
      SOUND.reversed().map { header ->
        aac().apply { setInteger(MediaFormat.KEY_TRACK_ID, header.id.toInt()) }
      }

    val tracks = audioTracksOf(formats, MovieHeaders(duration = null, tracks = HEADERS))

    tracks.map { it.id } shouldBe SOUND.reversed().map { AudioTrackId(it.id) }
    tracks.map { it.isDefault } shouldBe SOUND.reversed().map { it.isEnabled }
  }

  // A file that is no movie names its tracks by their place, whatever number the extractor gives them.
  @Test
  fun formatsOfAFileWithNoTrackHeadersAreNamedByTheirPlace() {
    val formats = List(SOUND.size) { aac().apply { setInteger(MediaFormat.KEY_TRACK_ID, PACKET_ID + it) } }

    val tracks = audioTracksOf(formats, MovieHeaders.None)

    tracks.map { it.id } shouldBe List(SOUND.size) { AudioTrackId(it + 1L) }
    tracks.map { it.isDefault } shouldBe List(SOUND.size) { false }
  }

  private fun aac(): MediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2)

  private companion object {
    const val PACKET_ID = 257

    val SOUND =
      listOf(
        TrackHeader(id = 2, isEnabled = false, handler = fourcc("soun"), language = "eng"),
        TrackHeader(id = 3, isEnabled = true, handler = fourcc("soun"), language = "fra"),
        TrackHeader(id = 4, isEnabled = false, handler = fourcc("soun"), language = "deu"),
      )

    val HEADERS = listOf(TrackHeader(id = 1, isEnabled = true, handler = fourcc("vide"), language = "und")) + SOUND
  }
}
