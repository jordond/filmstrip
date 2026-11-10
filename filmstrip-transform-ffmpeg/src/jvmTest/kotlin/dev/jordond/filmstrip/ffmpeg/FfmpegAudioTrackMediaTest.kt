package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.decodedAudio
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.ffmpeg.support.toneAt
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.defaultAudioTrackOf
import dev.jordond.filmstrip.test.AudioTrackFixture
import dev.jordond.filmstrip.test.ThreeAudio
import dev.jordond.filmstrip.test.ThreeAudioMatroska
import dev.jordond.filmstrip.test.ThreeAudioTs
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The audio tracks of a source with three of them, as ffprobe reads them and as an export plays the one a clip chose.
 */
class FfmpegAudioTrackMediaTest {
  private val fixture = FfmpegExportFixture()
  private val threeAudio = File(fixture.fixtures, ThreeAudio.FILE_NAME)
  private val source = MediaSource.of(threeAudio.absolutePath)

  @Test
  fun `the probe lists every audio track with its id language and default flag`() =
    runTest(timeout = TIMEOUT) {
      ThreeAudio.assertProbed(probe())
    }

  @Test
  fun `the probe reads a matroska file's tracks by their place and its own default flag`() =
    runTest(timeout = TIMEOUT) {
      ThreeAudioMatroska.assertProbed(probe(MediaSource.of(File(fixture.fixtures, ThreeAudioMatroska.fileName).path)))
    }

  @Test
  fun `the probe reads a transport stream's tracks by their place`() =
    runTest(timeout = TIMEOUT) {
      ThreeAudioTs.assertProbed(probe(MediaSource.of(File(fixture.fixtures, ThreeAudioTs.fileName).path)))
    }

  @Test
  fun `a re-encoded clip plays each track it names and none of the others`() =
    runTest(timeout = TIMEOUT) {
      ThreeAudio.tracks.forEach { track ->
        val audio = exported(Clip(source, effects = listOf(Brightness(0.1f)), audioTrack = track.id))

        assertHears(audio, track.id, "track ${track.id.value}")
      }
    }

  @Test
  fun `a re-encoded transport stream clip plays each track it names by its place`() =
    runTest(timeout = TIMEOUT) {
      val ts = MediaSource.of(File(fixture.fixtures, ThreeAudioTs.fileName).path)
      ThreeAudioTs.tracks.forEach { track ->
        val audio = exported(Clip(ts, effects = listOf(Brightness(0.1f)), audioTrack = track.id))

        assertHears(audio, track.id, "track ${track.id.value}", ThreeAudioTs)
      }
    }

  @Test
  fun `a clip that names no track plays the one the container flags`() =
    runTest(timeout = TIMEOUT) {
      val flagged = defaultAudioTrackOf(probe().audioTracks).shouldNotBeNull().id

      assertHears(exported(Clip(source, effects = listOf(Brightness(0.1f)))), flagged, "the default track")
    }

  @Test
  fun `a copy writes only the track the clip names`() =
    runTest(timeout = TIMEOUT) {
      val last = ThreeAudio.tracks.last().id
      val composition = Composition(listOf(Track(listOf(Clip(source, audioTrack = last)))))
      val plan =
        fixture.filmstrip
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan
      plan.path shouldBe ExportPath.Copy
      val output = File.createTempFile("filmstrip-track-copy", ".mp4").also { it.delete() }

      export(composition, output)

      audioStreamsOf(output) shouldBe 1
      assertHears(decodedAudio(output.absolutePath), last, "the copied track")
    }

  private suspend fun probe(of: MediaSource = source): MediaInfo =
    fixture.filmstrip
      .probe(of)
      .shouldBeInstanceOf<ProbeResult.Success>()
      .info

  private suspend fun exported(clip: Clip): FloatArray {
    val output = File.createTempFile("filmstrip-track", ".mp4").also { it.delete() }
    export(Composition(listOf(Track(listOf(clip)))), output)
    return decodedAudio(output.absolutePath)
  }

  private suspend fun export(
    composition: Composition,
    output: File,
  ) {
    val finished =
      fixture.filmstrip
        .export(composition, ExportSpec(), MediaSink.of(output.absolutePath))
        .toList()
        .last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
  }

  // The chosen tone stands well clear of the other two, which a track mixed in or swapped for another would not.
  private fun assertHears(
    audio: FloatArray,
    id: AudioTrackId,
    what: String,
    of: AudioTrackFixture = ThreeAudio,
  ) {
    val heard = audio.toneAt(READING, of.toneOf(id))
    of.tracks.filter { it.id != id }.forEach { other ->
      val leaked = audio.toneAt(READING, other.toneHz)
      withClue("$what at ${of.toneOf(id)} Hz read $heard against $leaked at ${other.toneHz} Hz") {
        heard shouldBeGreaterThan leaked * CLEAR_OF_THE_OTHERS
      }
    }
  }

  private fun audioStreamsOf(file: File): Int {
    val process =
      ProcessBuilder(
        hostFfprobe,
        "-v",
        "error",
        "-select_streams",
        "a",
        "-show_entries",
        "stream=index",
        "-of",
        "csv=p=0",
        file.absolutePath,
      ).start()
    val lines =
      process.inputStream
        .bufferedReader()
        .readLines()
        .filter { it.isNotBlank() }
    process.waitFor()
    return lines.size
  }

  private companion object {
    val READING = 1.seconds
    const val CLEAR_OF_THE_OTHERS = 10f
  }
}
