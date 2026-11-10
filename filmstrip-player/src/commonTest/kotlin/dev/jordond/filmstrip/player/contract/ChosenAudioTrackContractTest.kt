package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.ThreeAudio
import dev.jordond.filmstrip.test.ToneAnalysis
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThan
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Which of a source's audio tracks a preview plays, heard by the tone each track of [ThreeAudio] carries.
 *
 * Every engine whose preview audio a test can read back runs this. Apple reads the preview's own composition through
 * its audio mix, and media3 keeps what its player hands the audio sink. The ffmpeg preview plays no audio, so it has
 * nothing to hear. A headless browser refuses to start audio at all, so the browser's preview is heard in webcodecs'
 * `BrowserAudioTrackMediaTest` instead, through the stream its preview plays.
 */
abstract class ChosenAudioTrackContractTest(
  private val engine: PreviewAudioUnderTest,
) {
  @Test
  fun aPreviewPlaysEachTrackAClipNamesAndNoneOfTheOthers() =
    contractTest(timeout = TIMEOUT) {
      ThreeAudio.tracks.forEach { track ->
        assertHears(engine.heard(Clip(engine.source, audioTrack = track.id)), track.id, "track ${track.id.value}")
      }
    }

  @Test
  fun aPreviewOfAClipThatNamesNoTrackPlaysTheOneTheContainerFlags() =
    contractTest(timeout = TIMEOUT) {
      val flagged = ThreeAudio.tracks.single { it.isDefault }.id

      assertHears(engine.heard(Clip(engine.source)), flagged, "the default track")
    }

  // The chosen tone stands well clear of the other two, which a track mixed in or swapped for another would not.
  private fun assertHears(
    audio: HeardTone,
    id: AudioTrackId,
    what: String,
  ) {
    val heard = audio.amplitudeOf(ThreeAudio.toneOf(id))
    ThreeAudio.tracks.filter { it.id != id }.forEach { other ->
      val leaked = audio.amplitudeOf(other.toneHz)
      withClue("$what at ${ThreeAudio.toneOf(id)} Hz read $heard against $leaked at ${other.toneHz} Hz") {
        heard shouldBeGreaterThan leaked * CLEAR_OF_THE_OTHERS
      }
    }
  }

  private fun HeardTone.amplitudeOf(frequencyHz: Int): Float =
    ToneAnalysis.amplitudeOver(
      samples = samples,
      from = framesIn(READ_FROM),
      length = framesIn(READ_LENGTH),
      frequencyHz = frequencyHz,
      sampleRate = sampleRate,
    )

  private fun HeardTone.framesIn(length: Duration): Int =
    (length.inWholeMicroseconds * sampleRate / MICROS_PER_SECOND).toInt()

  private companion object {
    val TIMEOUT = 3.minutes

    // Inside the fixture's two seconds, clear of a decoder's fade in and the tail.
    val READ_FROM = 500.milliseconds
    val READ_LENGTH = 1_000.milliseconds
    const val CLEAR_OF_THE_OTHERS = 10f
    const val MICROS_PER_SECOND = 1_000_000L
  }
}

/**
 * An engine whose preview audio a test can read back.
 *
 * @property source The `three_audio` fixture as this engine opens it.
 */
interface PreviewAudioUnderTest {
  val source: MediaSource

  /**
   * What a preview of a composition holding only [clip] plays from its start.
   */
  suspend fun heard(clip: Clip): HeardTone
}

/**
 * One channel of what a preview played.
 *
 * @property samples Each frame's sample, on the -1 to 1 scale.
 * @property sampleRate Frames a second.
 */
class HeardTone(
  val samples: FloatArray,
  val sampleRate: Int,
)
