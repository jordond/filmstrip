package dev.jordond.filmstrip.media3

import android.media.MediaFormat
import android.net.Uri
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.defaultAudioTrackOf
import dev.jordond.filmstrip.test.AudioTrackFixture
import dev.jordond.filmstrip.test.ThreeAudio
import dev.jordond.filmstrip.test.ThreeAudioMatroska
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Which of a source's audio tracks a media3 export plays, heard by the tone each track of [ThreeAudio] carries, and of
 * [ThreeAudioMatroska], whose tracks are named by their place.
 */
class AndroidAudioTrackMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aReEncodedClipPlaysEachTrackItNamesAndNoneOfTheOthers() =
    runTest(timeout = TIMEOUT) {
      ThreeAudio.tracks.forEach { track ->
        val file =
          probe.export(
            compositionOf(Clip(source(), effects = listOf(Brightness(0.1f)), audioTrack = track.id)),
          )

        assertHears(file, track.id, "track ${track.id.value}")
      }
    }

  @Test
  fun aMatroskaClipPlaysEachTrackItNamesAndNoneOfTheOthers() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(ThreeAudioMatroska.fileName)
      ThreeAudioMatroska.tracks.forEach { track ->
        val file = probe.export(compositionOf(Clip(source, effects = listOf(Brightness(0.1f)), audioTrack = track.id)))

        assertHears(file, track.id, "matroska track ${track.id.value}", ThreeAudioMatroska)
      }
    }

  @Test
  fun aClipThatNamesNoTrackPlaysTheOneTheContainerFlags() =
    runTest(timeout = TIMEOUT) {
      val flagged = defaultAudioTrackOf(probe.infoOf(source()).audioTracks).shouldNotBeNull().id

      val file = probe.export(compositionOf(Clip(source(), effects = listOf(Brightness(0.1f)))))

      assertHears(file, flagged, "the default track")
    }

  @Test
  fun aCopyWritesOnlyTheTrackTheClipNames() =
    runTest(timeout = TIMEOUT) {
      val last = ThreeAudio.tracks.last().id
      val plan = probe.plan(compositionOf(Clip(source(), audioTrack = last)))
      plan.path shouldBe ExportPath.Copy

      val file = probe.export(plan)

      audioTracksIn(file) shouldBe 1
      assertHears(file, last, "the copied track")
    }

  private fun source() = probe.fixture(ThreeAudio.FILE_NAME)

  private fun compositionOf(clip: Clip) = Composition(listOf(Track(listOf(clip))))

  // The chosen tone stands well clear of the other two, which a track mixed in or swapped for another would not.
  private fun assertHears(
    file: File,
    id: AudioTrackId,
    what: String,
    fixture: AudioTrackFixture = ThreeAudio,
  ) {
    val audio = decodeAudio(file)
    file.delete()
    val heard = audio.amplitudeOver(READ_FROM, READ_LENGTH, fixture.toneOf(id))
    fixture.tracks.filter { it.id != id }.forEach { other ->
      val leaked = audio.amplitudeOver(READ_FROM, READ_LENGTH, other.toneHz)
      withClue("$what at ${fixture.toneOf(id)} Hz read $heard against $leaked at ${other.toneHz} Hz") {
        heard shouldBeGreaterThan leaked * CLEAR_OF_THE_OTHERS
      }
    }
  }

  private fun audioTracksIn(file: File): Int {
    val extractor = MediaExtractorCompat()
    return try {
      extractor.setDataSource(Uri.fromFile(file), 0L)
      (0 until extractor.trackCount).count {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      }
    } finally {
      extractor.release()
    }
  }

  private companion object {
    val TIMEOUT = 3.minutes

    // Inside the fixture's two seconds, clear of the encoder's fade in and its tail.
    val READ_FROM = 500.milliseconds
    val READ_LENGTH = 1_000.milliseconds
    const val CLEAR_OF_THE_OTHERS = 10f
  }
}
