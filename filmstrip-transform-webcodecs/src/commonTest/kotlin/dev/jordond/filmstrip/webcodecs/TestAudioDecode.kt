@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioSampleSink
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs

/**
 * What decoding [source]'s audio track back proved: real samples with a real duration and a peak
 * amplitude, or null when the source has no audio track at all.
 *
 * [startUs] and [durationUs] are where the track opens and where it ends, both on the file's own
 * timeline, which is what a test comparing the audio against the video reads.
 */
internal class DecodedAudio(
  val sampleCount: Int,
  val startUs: Double,
  val durationUs: Double,
  val peakAmplitude: Float,
)

/**
 * The first channel of an audio track decoded back into one array, and where it ends on the file's timeline.
 */
internal class DecodedSamples(
  val samples: FloatArray,
  val sampleRate: Int,
  val durationUs: Double,
)

internal suspend fun decodeAudioSamples(source: MediaSource): DecodedSamples {
  val reader = SourceReader.of(source) ?: error("the decoder could not open $source")
  try {
    val track = reader.firstAudioTrack() ?: error("$source has no audio track")
    val sampleRate =
      track
        .getSampleRate()
        .await()
        .toDouble()
        .toInt()
    val iterator = AudioSampleSink(track).samples(0.0, Double.POSITIVE_INFINITY)
    val chunks = mutableListOf<FloatArray>()
    var durationUs = 0.0
    while (true) {
      val step = iterator.next().await()
      if (step.done) break
      val sample = step.value ?: continue
      try {
        durationUs = maxOf(durationUs, sample.microsecondTimestamp + sample.microsecondDuration)
        val data = sample.toAudioBuffer().getChannelData(0)
        chunks += FloatArray(data.length) { data.at(it) }
      } finally {
        sample.close()
      }
    }
    val joined = FloatArray(chunks.sumOf { it.size })
    var at = 0
    chunks.forEach { chunk ->
      chunk.copyInto(joined, at)
      at += chunk.size
    }
    return DecodedSamples(joined, sampleRate, durationUs)
  } finally {
    reader.close()
  }
}

internal suspend fun decodeAudio(source: MediaSource): DecodedAudio? {
  val reader = SourceReader.of(source) ?: error("the decoder could not open $source")
  try {
    val track = reader.firstAudioTrack() ?: return null
    val sink = AudioSampleSink(track)
    val iterator = sink.samples(0.0, Double.POSITIVE_INFINITY)
    var count = 0
    var startUs = Double.POSITIVE_INFINITY
    var durationUs = 0.0
    var peak = 0f
    while (true) {
      val step = iterator.next().await()
      if (step.done) break
      val sample = step.value ?: continue
      try {
        count++
        startUs = minOf(startUs, sample.microsecondTimestamp)
        durationUs = maxOf(durationUs, sample.microsecondTimestamp + sample.microsecondDuration)
        val buffer = sample.toAudioBuffer()
        for (channel in 0 until buffer.numberOfChannels) {
          val data = buffer.getChannelData(channel)
          for (index in 0 until data.length) {
            peak = maxOf(peak, abs(data.at(index)))
          }
        }
      } finally {
        sample.close()
      }
    }
    return DecodedAudio(count, if (count == 0) 0.0 else startUs, durationUs, peak)
  } finally {
    reader.close()
  }
}
