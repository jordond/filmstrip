package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.defaultAudioTrackOf
import dev.jordond.filmstrip.test.AudioTrackFixture
import dev.jordond.filmstrip.test.ThreeAudio
import dev.jordond.filmstrip.test.ThreeAudioTs
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Which audio track of a source with three of them an export plays, heard by the tone each track carries, in an MP4
 * and in a transport stream whose tracks are named by their place.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleAudioTrackMediaTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }
  private val source = MediaSource.of(fixture(ThreeAudio.FILE_NAME))

  @Test
  fun `a re-encoded clip plays each track it names and none of the others`() =
    runTest(timeout = TIMEOUT) {
      ThreeAudio.tracks.forEach { track ->
        val audio = exported(Clip(source, effects = listOf(Brightness(0.1f)), audioTrack = track.id), "each")

        assertHears(audio, track.id, "track ${track.id.value}")
      }
    }

  @Test
  fun `a transport stream clip plays each track it names and none of the others`() =
    runTest(timeout = TIMEOUT) {
      val stream = MediaSource.of(fixture(ThreeAudioTs.fileName))
      ThreeAudioTs.tracks.forEach { track ->
        val audio = exported(Clip(stream, effects = listOf(Brightness(0.1f)), audioTrack = track.id), "ts")

        assertHears(audio, track.id, "transport stream track ${track.id.value}", ThreeAudioTs)
      }
    }

  @Test
  fun `a clip that names no track plays the one the container flags`() =
    runTest(timeout = TIMEOUT) {
      val info = filmstrip.probe(source).shouldBeInstanceOf<ProbeResult.Success>().info
      val flagged = defaultAudioTrackOf(info.audioTracks).shouldNotBeNull().id

      assertHears(exported(Clip(source, effects = listOf(Brightness(0.1f))), "default"), flagged, "the default track")
    }

  @Test
  fun `a clip at half speed stretches the track it names`() =
    runTest(timeout = TIMEOUT) {
      val last = ThreeAudio.tracks.last().id

      assertHears(exported(Clip(source, audioTrack = last, speed = HALF), "stretched"), last, "the stretched track")
    }

  @Test
  fun `a copy writes only the track the clip names`() =
    runTest(timeout = TIMEOUT) {
      val first = ThreeAudio.tracks.first().id
      val composition = Composition(listOf(Track(listOf(Clip(source, audioTrack = first)))))
      val plan =
        filmstrip
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan
      plan.path shouldBe ExportPath.Copy
      val output = NSTemporaryDirectory() + "filmstrip-apple-track-copy.mp4"

      export(composition, output)

      AVURLAsset(uRL = NSURL.fileURLWithPath(output), options = null)
        .tracksWithMediaType(AVMediaTypeAudio)
        .size shouldBe 1
      assertHears(audioOf(output), first, "the copied track")
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    }

  private suspend fun exported(
    clip: Clip,
    name: String,
  ): AudioProbe {
    val output = NSTemporaryDirectory() + "filmstrip-apple-track-$name.mp4"
    export(Composition(listOf(Track(listOf(clip)))), output)
    return audioOf(output).also { NSFileManager.defaultManager.removeItemAtPath(output, error = null) }
  }

  private suspend fun export(
    composition: Composition,
    output: String,
  ) = withContext(Dispatchers.Default) {
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    val finished = filmstrip.export(composition, ExportSpec(), MediaSink.of(output)).toList().last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
  }

  // The chosen tone stands well clear of the other two, which a track mixed in or swapped for another would not.
  private fun assertHears(
    audio: AudioProbe,
    id: AudioTrackId,
    what: String,
    fixture: AudioTrackFixture = ThreeAudio,
  ) {
    val heard = audio.toneOver(READING, WINDOW, fixture.toneOf(id))
    fixture.tracks.filter { it.id != id }.forEach { other ->
      val leaked = audio.toneOver(READING, WINDOW, other.toneHz)
      withClue("$what at ${fixture.toneOf(id)} Hz read $heard against $leaked at ${other.toneHz} Hz") {
        heard shouldBeGreaterThan leaked * CLEAR_OF_THE_OTHERS
      }
    }
  }

  private companion object {
    val TIMEOUT = 3.minutes
    val READING = 1.seconds
    val WINDOW = 200.milliseconds
    const val HALF = 0.5
    const val CLEAR_OF_THE_OTHERS = 10f
  }
}
