package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.composition.compositorOutputAttributes
import dev.jordond.filmstrip.avfoundation.internal.composition.compositorSourceAttributes
import dev.jordond.filmstrip.avfoundation.internal.still.stillSeedBufferAttributes
import dev.jordond.filmstrip.avfoundation.internal.writer.audioWriterSettings
import dev.jordond.filmstrip.avfoundation.internal.writer.pcmReaderSettings
import dev.jordond.filmstrip.avfoundation.internal.writer.videoReaderSettings
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.test.Test

/**
 * Every dictionary this backend hands to AVFoundation is keyed by strings.
 *
 * The AVFoundation constants are `NSString` and reach a Kotlin map as one. The CoreVideo and
 * VideoToolbox ones are `CFStringRef`, which is a pointer, and a map holding one bridges into a
 * dictionary AVFoundation reads as carrying nothing it recognises. macOS ignores such a dictionary
 * and iOS fails the whole write, so this is a difference no host test would otherwise catch.
 */
class AvSettingsKeyTest {
  @Test
  fun `the video reader settings are keyed by strings`() {
    videoReaderSettings(encodesHdr = false).assertStringKeyed()
    videoReaderSettings(encodesHdr = true).assertStringKeyed()
  }

  @Test
  fun `the compositor's buffer attributes are keyed by strings`() {
    compositorSourceAttributes().assertStringKeyed()
    compositorOutputAttributes().assertStringKeyed()
  }

  @Test
  fun `the still seed's buffer attributes are keyed by strings`() {
    stillSeedBufferAttributes().assertStringKeyed()
  }

  @Test
  fun `the audio settings are keyed by strings`() {
    audioWriterSettings(output(AudioCodec.Aac))?.assertStringKeyed()
    pcmReaderSettings(output(AudioCodec.Aac)).assertStringKeyed()
  }

  private fun Map<Any?, Any?>.assertStringKeyed() {
    withClue("an empty dictionary proves nothing") { isNotEmpty().shouldBeTrue() }
    keys.forEach { key ->
      withClue("$key is a ${key?.let { it::class.simpleName }}, not a string") { (key is String).shouldBeTrue() }
    }
  }

  private fun output(audio: AudioCodec) =
    OutputFormat(
      size = Size(1280, 720),
      videoCodec = VideoCodec.Hevc,
      audioCodec = audio,
      bitrate = null,
      frameRate = 30,
      audioFormat = AudioFormat(sampleRate = 48_000, channelCount = 2),
    )
}
