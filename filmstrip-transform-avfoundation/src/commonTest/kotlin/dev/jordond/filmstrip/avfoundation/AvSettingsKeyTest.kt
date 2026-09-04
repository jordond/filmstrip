package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.audioWriterSettings
import dev.jordond.filmstrip.avfoundation.internal.pcmReaderSettings
import dev.jordond.filmstrip.avfoundation.internal.stillSeedBufferAttributes
import dev.jordond.filmstrip.avfoundation.internal.videoReaderSettings
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertTrue

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
  fun `the still seed's buffer attributes are keyed by strings`() {
    stillSeedBufferAttributes().assertStringKeyed()
  }

  @Test
  fun `the audio settings are keyed by strings`() {
    audioWriterSettings(output(AudioCodec.Aac))?.assertStringKeyed()
    pcmReaderSettings(output(AudioCodec.Aac)).assertStringKeyed()
  }

  private fun Map<Any?, Any?>.assertStringKeyed() {
    assertTrue(isNotEmpty(), "an empty dictionary proves nothing")
    keys.forEach { key ->
      assertTrue(key is String, "$key is a ${key?.let { it::class.simpleName }}, not a string")
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
