package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.spatial.Vignette
import dev.jordond.filmstrip.ffmpeg.support.FILM_READ_SLOT
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.alphaAt
import dev.jordond.filmstrip.ffmpeg.support.alphaRampClip
import dev.jordond.filmstrip.ffmpeg.support.flatAlphaClip
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Reads the alpha a clip's own look hands on, straight off the look's output, against the clip's own alpha.
 *
 * The clip's alpha steps one code a column from clear to opaque, so every code from 129 up, where a sixteen bit sample
 * read over 257 lands a code low, is read once a row. None of these looks touches alpha, so every column reads its own
 * code exactly.
 */
class FfmpegLookAlphaMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a clip without a look hands its alpha on exactly`() = assertAlphaKept(emptyList(), 0)

  @Test
  fun `a clip film with its gate on keeps the clip alpha exactly`() =
    assertAlphaKept(listOf(STILL_SUPER8), FILM_READ_SLOT)

  @Test
  fun `a clip vignette keeps the clip alpha exactly`() = assertAlphaKept(listOf(Vignette()), 0)

  @Test
  fun `a clip night vision keeps the clip alpha exactly`() =
    assertAlphaKept(listOf(NightVision(intensity = 0.5f, seed = 7)), FILM_READ_SLOT)

  // The tube reads between pixels where the curve bends the frame, so a ramp would move. A flat alpha holds wherever
  // it is read, here at the bottom, middle and top of the codes a read over 257 loses.
  @Test
  fun `a clip crt keeps a flat clip alpha exactly`() =
    runBlocking<Unit> {
      FLAT_CODES.forEach { code ->
        val clip = draw.flatAlphaClip(WIDTH, HEIGHT, code, listOf(Crt(MID)))
        val alpha = draw.alphaAt(draw.composition(listOf(clip)), 0)
        val lost = alpha.indices.filter { alpha[it] != code }
        withClue("code $code loses ${lost.size} samples, first ${lost.take(LISTED).map { alpha[it] }}") {
          lost.size shouldBe 0
        }
      }
    }

  private fun assertAlphaKept(
    look: List<EffectSpec>,
    slot: Int,
  ) = runBlocking<Unit> {
    val clip: Clip = draw.alphaRampClip(WIDTH, HEIGHT, look)
    val alpha = draw.alphaAt(draw.composition(listOf(clip)), slot)
    val lost = alpha.indices.filter { alpha[it] != it % WIDTH }
    withClue("$look loses ${lost.size} samples, first ${lost.take(LISTED).map { it % WIDTH to alpha[it] }}") {
      lost.size shouldBe 0
    }
  }

  private companion object {
    const val WIDTH = 256
    const val HEIGHT = 64
    const val LISTED = 8
    const val MID = 0.5f
    val FLAT_CODES = listOf(129, 192, 255)

    // Super 8 with its weave off, since a weave reads between pixels and moves a ramp's alpha.
    val STILL_SUPER8 =
      Film(
        grain = Film.Super8.grain,
        halation = Film.Super8.halation,
        flicker = Film.Super8.flicker,
        dust = Film.Super8.dust,
        gate = Film.Super8.gate,
      )
  }
}
