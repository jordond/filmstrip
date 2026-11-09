package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * One export per path the wasm bindings cross on the way to a file: a video frame through the
 * compositor and encoder, audio through the mixer and encoder, and compressed packets straight into
 * the muxer.
 */
class WasmExportCanaryMediaTest {
  @Test
  fun aGradedFrameCrossesTheCompositorAndEncoder() =
    runTest {
      val bytes = makeClip(color = Rgb.Grey)
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = listOf(Brightness(HALF_BRIGHTNESS)))))),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frame = decodeFrames(outputOf(success)).firstOrNull().shouldNotBeNull()
      val dimmed = frame.at(x = 0.5, y = 0.5)
      val expected = Rgb(Rgb.Grey.red / 2, Rgb.Grey.green / 2, Rgb.Grey.blue / 2)
      withClue("a half-brightness clip encoded as $dimmed, and half of ${Rgb.Grey} is $expected") {
        dimmed.isNear(expected) shouldBe true
      }
    }

  @Test
  fun aTrimmedClipEncodesAudibleAudio() =
    runTest {
      val bytes = makeClipWithAudio(frames = 60, frameRate = 30)
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), trim = TimeRange(TRIM_START, TRIM_END))))),
          audio = AudioSpec.Keep,
        )
      val spec = autoAudioSpec()
      val success = exportOf(composition, MediaSink.Uri(""), spec)

      shouldCarryAudioIn(success, plannedAudioOf(composition, spec))
      val audio =
        withClue("the export produced no readable audio track") {
          decodeAudio(outputOf(success)).shouldNotBeNull()
        }
      withClue("the export's audio peaked at ${audio.peakAmplitude}, expected an audible tone") {
        (audio.peakAmplitude > SILENCE_THRESHOLD) shouldBe true
      }
    }

  @Test
  fun anUntouchedClipMuxesItsPacketsUnchanged() =
    runTest {
      val bytes = makeClip()
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val sourcePackets = readEncodedVideoPackets(MediaSource.Bytes(bytes))
      val copiedPackets = readEncodedVideoPackets(outputOf(success))
      withClue("the copy carried a different packet count") { copiedPackets.size shouldBe sourcePackets.size }
      sourcePackets.zip(copiedPackets).forEachIndexed { index, (expected, actual) ->
        withClue("packet $index differed between the source and the copy") {
          expected.contentEquals(actual) shouldBe true
        }
      }
    }
}
