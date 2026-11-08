package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.commandOf
import dev.jordond.filmstrip.ffmpeg.support.rawFrames
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * A plan with a look runs each decoder on one thread, a plain plan keeps ffmpeg's count, and the flag moves no sample.
 */
class FfmpegLookDecoderMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a look pins its decoders ahead of each input`() =
    runBlocking<Unit> { decoderFlagOf(listOf(Film())) shouldBe listOf("1") }

  @Test
  fun `a plain plan and an inert look leave the decoder alone`() =
    runBlocking<Unit> {
      decoderFlagOf(emptyList()) shouldBe emptyList()
      decoderFlagOf(listOf(Film(grain = 0f, halation = 0f))) shouldBe emptyList()
    }

  @Test
  fun `one decoder thread moves no sample of a film`() = runBlocking<Unit> { assertFlagMovesNothing(Film()) }

  @Test
  fun `one decoder thread moves no sample of a vhs`() = runBlocking<Unit> { assertFlagMovesNothing(Vhs()) }

  private suspend fun decoderFlagOf(effects: List<EffectSpec>): List<String> {
    val command = draw.commandOf(draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::grey, effects))))
    val firstInput = command.indexOf("-i")
    return command
      .take(firstInput)
      .windowed(2)
      .filter { it.first() == "-threads" }
      .map { it.last() }
  }

  private suspend fun assertFlagMovesNothing(look: EffectSpec) {
    val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::grey, listOf(look))))
    val pinned = draw.rawFrames(composition, FRAMES)
    val free =
      draw.rawFrames(composition, FRAMES) { command ->
        val flag = command.indexOf("-threads")
        command.take(flag) + command.drop(flag + 2)
      }
    pinned.contentEquals(free) shouldBe true
  }

  private fun grey(fraction: Double): DoubleArray = doubleArrayOf(fraction, 0.5, 1.0 - fraction)

  private companion object {
    const val WIDTH = 320
    const val HEIGHT = 180
    const val FRAMES = 6
  }
}
