package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.export.AudioFormat
import kotlin.math.abs
import kotlin.math.floor

// The kernel that plays spec on one stream at format.
internal fun tapeAudioKernel(
  spec: TapeAudio,
  format: AudioFormat,
): AudioKernel = TapeAudioKernel(tapeAudioParams(spec, format.sampleRate), format.channelCount)

// Plays one stream through a tape: the mono fold, a delay the wow and flutter bend, the high-pass, the hiss, then the
// low-pass over both.
internal class TapeAudioKernel(
  private val params: TapeAudioParams,
  private val channels: Int,
) : AudioKernel() {
  private val mask = ringFramesFor(params.delayFrames) - 1
  private val ring = FloatArray((mask + 1) * channels)
  private var write = 0

  private val highPass = params.highPass
  private val lowPass = params.lowPass
  private val sections = 1 + lowPass.size
  private val band = DoubleArray(channels * sections * 2)

  private val hissChannels = if (params.hissPerChannel) channels else 1
  private val hiss = DoubleArray(hissChannels)
  private val taps = DoubleArray(TAPS)

  override val latencyFrames: Int get() = params.delayFrames

  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) {
    for (frame in 0 until frames) {
      val at = frame * channels
      fold(buffer, at)
      for (channel in 0 until channels) ring[write * channels + channel] = buffer[at + channel]
      val whole = aim(tapeDelayAt(params, position + frame))
      drawHiss(position + frame)
      for (channel in 0 until channels) {
        var sample = 0.0
        for (tap in 0 until TAPS) {
          sample += taps[tap] * ring[((write - whole + 1 - tap) and mask) * channels + channel]
        }
        buffer[at + channel] = limit(sample, hiss[if (params.hissPerChannel) channel else 0], channel).toFloat()
      }
      write = (write + 1) and mask
    }
  }

  override fun reset(position: Long) {
    ring.fill(0f)
    band.fill(0.0)
    write = 0
  }

  private fun fold(
    buffer: FloatArray,
    at: Int,
  ) {
    if (params.monoMix == 0.0 || channels == 1) return
    var sum = 0.0
    for (channel in 0 until channels) sum += buffer[at + channel]
    val mean = sum / channels
    for (channel in 0 until channels) {
      val sample = buffer[at + channel]
      buffer[at + channel] = (sample + params.monoMix * (mean - sample)).toFloat()
    }
  }

  // Sets the four weights that read the ring at delay, and answers its whole frames. The read is a cubic through the
  // frames either side, whose second weight is one when the delay is whole.
  private fun aim(delay: Double): Int {
    val whole = floor(delay)
    val d = delay - whole + 1.0
    taps[0] = -(d - 1.0) * (d - 2.0) * (d - 3.0) / 6.0
    taps[1] = d * (d - 2.0) * (d - 3.0) / 2.0
    taps[2] = -d * (d - 1.0) * (d - 3.0) / 2.0
    taps[3] = d * (d - 1.0) * (d - 2.0) / 6.0
    return whole.toInt()
  }

  // The hiss joins ahead of the low-pass, which is the same sound as a hiss with a low-pass of its own.
  private fun limit(
    input: Double,
    hiss: Double,
    channel: Int,
  ): Double {
    if (!params.bandLimited) return input + hiss
    val at = channel * sections * 2
    var sample = highPass.step(input, band, at) + hiss
    for (index in lowPass.indices) sample = lowPass[index].step(sample, band, at + (index + 1) * 2)
    for (index in at until at + sections * 2) if (abs(band[index]) < SILENT) band[index] = 0.0
    return sample
  }

  private fun drawHiss(frame: Long) {
    if (params.hissGain == 0.0) return
    for (channel in 0 until hissChannels) hiss[channel] = params.hissGain * tapeNoiseAt(params.seed, channel, frame)
  }
}

// A power of two that holds the furthest read: twice the centre less its margin, and the taps past it.
private fun ringFramesFor(delayFrames: Int): Int {
  var size = MIN_RING_FRAMES
  while (size < delayFrames * 2 + TAPS) size *= 2
  return size
}

private const val TAPS = 4
private const val MIN_RING_FRAMES = 16

// Under this a filter's memory is silence. Left alone it decays into numbers some processors are slow on.
private const val SILENT = 1e-30
