package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.export.AudioFormat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * An audio effect that replaces what it is given with steady tones for the first [length] of the composition and
 * silence after.
 *
 * Declared ahead of the effect under test it gives every backend the same input, whose phase is a function of the
 * composition time, so what comes out says what the effect did to a known sound.
 *
 * @property frequenciesHz The tones, each a sine that opens at zero at the start of the composition.
 * @property amplitude The peak of each tone, on the -1 to 1 scale.
 * @property length How long the tones last, from the start of the composition.
 */
public data class TestTones(
  val frequenciesHz: List<Int>,
  val amplitude: Float,
  val length: Duration,
) : AudioEffectSpec {
  override val id: String get() = ID

  /**
   * Builds the kernel for one stream at [format].
   */
  public fun newKernel(format: AudioFormat): AudioKernel = TonesKernel(format, this)

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public const val ID: String = "test.tones"
  }
}

private class TonesKernel(
  private val format: AudioFormat,
  private val tones: TestTones,
) : AudioKernel() {
  private val frames = (tones.length.toDouble(DurationUnit.SECONDS) * format.sampleRate).toLong()

  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) {
    for (frame in 0 until frames) {
      val at = position + frame
      var sample = 0.0
      if (at < this.frames) {
        for (hz in tones.frequenciesHz) sample += sin(2 * PI * hz * at / format.sampleRate)
      }
      val value = (tones.amplitude * sample).toFloat()
      for (channel in 0 until format.channelCount) buffer[frame * format.channelCount + channel] = value
    }
  }
}
