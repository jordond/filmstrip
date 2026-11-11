package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.export.AudioFormat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * An audio effect that replaces what it is given with bursts of a tone laid out on the composition timeline.
 *
 * A burst of [TONE_HZ] at [amplitude] opens every [PERIOD] of composition time and lasts [LENGTH], with silence
 * between. Where a burst is heard in an export or a preview says which timeline position the backend handed the
 * kernel, and how loud it is says what ran after the effect.
 *
 * @property amplitude The peak of the tone, on the -1 to 1 scale.
 */
public data class TimelineBursts(
  val amplitude: Float,
) : AudioEffectSpec {
  override val id: String get() = ID

  /**
   * Builds the kernel for one stream at [format].
   */
  public fun newKernel(format: AudioFormat): AudioKernel = BurstKernel(format, amplitude)

  public companion object {
    public const val ID: String = "test.timeline-bursts"
    public const val TONE_HZ: Int = 1_000
    public val PERIOD: Duration = 400.milliseconds
    public val LENGTH: Duration = 80.milliseconds

    /**
     * The composition time burst [index] opens at.
     */
    public fun burstStart(index: Int): Duration = PERIOD * index
  }
}

private class BurstKernel(
  private val format: AudioFormat,
  private val amplitude: Float,
) : AudioKernel() {
  private val periodFrames = TimelineBursts.PERIOD.inWholeMilliseconds * format.sampleRate / MILLIS_PER_SECOND
  private val burstFrames = TimelineBursts.LENGTH.inWholeMilliseconds * format.sampleRate / MILLIS_PER_SECOND

  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) {
    for (frame in 0 until frames) {
      val at = position + frame
      val sample =
        if (at.mod(periodFrames) < burstFrames) {
          amplitude * sin(2 * PI * TimelineBursts.TONE_HZ * at / format.sampleRate).toFloat()
        } else {
          0f
        }
      for (channel in 0 until format.channelCount) buffer[frame * format.channelCount + channel] = sample
    }
  }
}

private const val MILLIS_PER_SECOND = 1_000L
