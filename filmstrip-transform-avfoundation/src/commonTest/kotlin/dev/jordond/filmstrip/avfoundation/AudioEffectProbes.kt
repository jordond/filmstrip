package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.test.TimelineBursts
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Delays the stream by [frames] and reports that as its latency, which a host takes out again.
 */
internal data class Delay(
  val frames: Int,
) : AudioEffectSpec {
  override val id: String get() = "test.delay"
}

/**
 * Adds [TimelineBursts] at [amplitude] to what it is given, so it is a linear effect plus a sound of its own.
 */
internal data class AddedBursts(
  val amplitude: Float,
) : AudioEffectSpec {
  override val id: String get() = "test.added-bursts"
}

/**
 * An effect whose kernel throws on the first block it is handed.
 */
internal data object Broken : AudioEffectSpec {
  override val id: String get() = "test.broken"
}

/**
 * What a [Broken] kernel throws with.
 */
internal const val THROWN: String = "this kernel always throws"

/**
 * Resolves every test effect in this file and [TimelineBursts] to its kernel.
 */
internal val testAudioEffects: AudioEffectResolver =
  AudioEffectResolver { spec, _ ->
    when (spec) {
      is TimelineBursts -> AudioEffectResolution.Resolved(PlatformAudioEffect(spec::newKernel))
      is Delay -> AudioEffectResolution.Resolved(PlatformAudioEffect { DelayKernel(it, spec.frames) })
      is AddedBursts -> AudioEffectResolution.Resolved(PlatformAudioEffect { AddedBurstsKernel(it, spec.amplitude) })
      is Broken -> AudioEffectResolution.Resolved(PlatformAudioEffect { ThrowingKernel() })
      else -> null
    }
  }

/**
 * The 1 kHz level over the middle of burst [index], clear of the edges an encoder smears.
 */
internal fun AudioProbe.burst(index: Int): Float =
  toneOver(
    TimelineBursts.burstStart(index) + BURST_EDGE,
    TimelineBursts.LENGTH - BURST_EDGE * 2,
    TimelineBursts.TONE_HZ,
  )

/**
 * The 1 kHz level over the middle of the silence after burst [index].
 */
internal fun AudioProbe.gap(index: Int): Float =
  toneOver(
    TimelineBursts.burstStart(index) + TimelineBursts.LENGTH + BURST_EDGE,
    TimelineBursts.PERIOD - TimelineBursts.LENGTH - BURST_EDGE * 2,
    TimelineBursts.TONE_HZ,
  )

/**
 * Asserts each of [bursts] is heard at [level] where it belongs on the timeline, with silence after it.
 */
internal fun AudioProbe.shouldHearBursts(
  bursts: IntRange,
  level: Float,
) {
  bursts.forEach { index ->
    withClue("burst $index, opening at ${TimelineBursts.burstStart(index)}") {
      burst(index) shouldBe (level plusOrMinus level * BURST_TOLERANCE)
    }
    withClue("the silence after burst $index") { gap(index) shouldBeLessThan level * BURST_SILENT }
  }
}

private class AddedBurstsKernel(
  format: AudioFormat,
  amplitude: Float,
) : AudioKernel() {
  private val bursts = TimelineBursts(amplitude).newKernel(format)
  private val channels = format.channelCount
  private var given = FloatArray(0)

  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) {
    if (given.size < frames * channels) given = FloatArray(frames * channels)
    buffer.copyInto(given, 0, 0, frames * channels)
    bursts.process(buffer, frames, position)
    for (index in 0 until frames * channels) buffer[index] += given[index]
  }
}

private class ThrowingKernel : AudioKernel() {
  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ): Unit = error(THROWN)
}

private val BURST_EDGE: Duration = 20.milliseconds

// An AAC encoder holds a steady tone's level to a few percent.
private const val BURST_TOLERANCE = 0.15f
private const val BURST_SILENT = 0.05f
