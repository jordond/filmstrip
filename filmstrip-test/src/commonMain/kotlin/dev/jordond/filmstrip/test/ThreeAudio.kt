package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.defaultAudioTrackOf
import kotlin.test.fail

/**
 * What a fixture with one video track and then three audio tracks carries, which every prober is checked against and
 * every backend's track choice test hears.
 *
 * Each audio track is a steady tone at its own frequency and in its own language, with only the middle one flagged as
 * the default. A backend that takes the first track instead of the default one, or plays a track other than the one
 * asked for, is heard at the wrong frequency.
 *
 * @property fileName The fixture's file name in every module that publishes it.
 * @property tracks The audio tracks, in the order the container lists them.
 */
@InternalFilmstripApi
public open class AudioTrackFixture(
  public val fileName: String,
  public val tracks: List<ExpectedAudioTrack>,
) {
  /**
   * The tone of the track named [id].
   */
  public fun toneOf(id: AudioTrackId): Int = tracks.single { it.id == id }.toneHz

  /**
   * Fails unless [info] lists the fixture's audio tracks with the ids, order, languages and default flags it was
   * written with, and reads the flagged one, or the first when none is flagged, as its default.
   *
   * @param readsLanguage False for a prober that reports no language for this container, whose languages are then not
   * compared.
   */
  public fun assertProbed(
    info: MediaInfo,
    readsLanguage: Boolean = true,
  ) {
    val read = info.audioTracks.map { ExpectedAudioTrack(it.id, it.language, toneHz = 0, isDefault = it.isDefault) }
    val written = tracks.map { it.copy(toneHz = 0) }
    val compared = if (readsLanguage) read to written else read.withoutLanguage() to written.withoutLanguage()
    if (compared.first != compared.second) {
      fail("The probe read the audio tracks as $read, and the fixture carries $written.")
    }
    val default = defaultAudioTrackOf(info.audioTracks)?.id
    val flagged = (tracks.firstOrNull { it.isDefault } ?: tracks.first()).id
    if (default != flagged) fail("The probe reads track $default as the default, and the fixture flags $flagged.")
    if (info.audio?.id != flagged) fail("The probe reports track ${info.audio?.id} as its audio, not $flagged.")
  }
}

/**
 * The `three_audio` fixture, an MP4 whose audio tracks are named by the track ids in their track headers.
 */
@InternalFilmstripApi
public object ThreeAudio : AudioTrackFixture(
  fileName = "three_audio.mp4",
  tracks =
    listOf(
      ExpectedAudioTrack(AudioTrackId(2), language = "eng", toneHz = 440, isDefault = false),
      ExpectedAudioTrack(AudioTrackId(3), language = "fra", toneHz = 660, isDefault = true),
      ExpectedAudioTrack(AudioTrackId(4), language = "deu", toneHz = 990, isDefault = false),
    ),
) {
  /**
   * The fixture's file name in every module that publishes it.
   */
  public const val FILE_NAME: String = "three_audio.mp4"
}

/**
 * The `three_audio_matroska` fixture, the same tracks as [ThreeAudio] written as Matroska.
 *
 * Only an MP4 or QuickTime file's track ids name its tracks, so these are named by their place among the audio tracks,
 * counted from one. The default flag is Matroska's own.
 */
@InternalFilmstripApi
public object ThreeAudioMatroska : AudioTrackFixture(
  fileName = "three_audio_matroska.mkv",
  tracks = ThreeAudio.tracks.mapIndexed { ordinal, track -> track.copy(id = AudioTrackId(ordinal + 1L)) },
)

/**
 * The `three_audio_ts` fixture, the same tracks as [ThreeAudio] written as an MPEG transport stream.
 *
 * A transport stream numbers its streams by packet id, which no other container reads, so these are named by their
 * place among the audio tracks, counted from one. It carries no default flag, so none is flagged and the first track
 * is the default.
 */
@InternalFilmstripApi
public object ThreeAudioTs : AudioTrackFixture(
  fileName = "three_audio_ts.ts",
  tracks = ThreeAudioMatroska.tracks.map { it.copy(isDefault = false) },
)

private fun List<ExpectedAudioTrack>.withoutLanguage(): List<ExpectedAudioTrack> = map { it.copy(language = null) }

/**
 * One audio track of an [AudioTrackFixture].
 *
 * @property id The id a probe names it by.
 * @property language Its ISO 639-2 language code.
 * @property toneHz The frequency of the tone it carries.
 * @property isDefault Whether the container flags it as the default.
 */
@InternalFilmstripApi
public data class ExpectedAudioTrack(
  val id: AudioTrackId,
  val language: String?,
  val toneHz: Int,
  val isDefault: Boolean,
)
