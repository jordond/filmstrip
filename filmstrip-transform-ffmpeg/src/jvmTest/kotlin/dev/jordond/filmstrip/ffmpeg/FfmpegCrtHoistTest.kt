package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A look every clip runs moves past the join only where it writes the same samples there.
 */
class FfmpegCrtHoistTest {
  private val draw = FilterFrames()

  @Test
  fun `two crt clips are hoisted and write what each clip writes alone`() =
    runBlocking<Unit> {
      val one = clip(::rising, Crt())
      val other = clip(::falling, Crt())
      val joined = draw.composition(listOf(one, other))
      draw.graphOf(joined) shouldContain HOISTED

      val frames = FILTER_RATE * CLIP_SECONDS
      val together = draw.formPlanes(joined, frames * 2, live = false)
      val apart =
        draw.formPlanes(draw.composition(listOf(one)), frames, live = false) +
          draw.formPlanes(draw.composition(listOf(other)), frames, live = false)

      withClue("the joined crt against each clip alone") { together.contentEquals(apart) shouldBe true }
    }

  @Test
  fun `a look that reads the composition clock stays on each clip`() =
    runBlocking<Unit> {
      listOf<EffectSpec>(Film(), Vhs(), Film(grain = 0f, halation = 0.5f)).forEach { look ->
        draw.graphOf(draw.composition(listOf(clip(::rising, look), clip(::falling, look)))) shouldNotContain HOISTED
      }
    }

  @Test
  fun `two sizes stay on each clip`() =
    runBlocking<Unit> {
      val small = draw.clipOf(WIDTH / 2, HEIGHT / 2, ::rising, listOf(Crt()))
      draw.graphOf(draw.composition(listOf(clip(::rising, Crt()), small))) shouldNotContain HOISTED
    }

  @Test
  fun `a different effect stays on each clip`() =
    runBlocking<Unit> {
      val plain = draw.composition(listOf(clip(::rising, Crt()), clip(::falling)))
      val other = draw.composition(listOf(clip(::rising, Crt()), clip(::falling, Crt(intensity = 0.5f))))
      val extra = draw.composition(listOf(clip(::rising, Crt()), clip(::falling, Crt(), Brightness(0.1f))))
      listOf(plain, other, extra).forEach { draw.graphOf(it) shouldNotContain HOISTED }
    }

  @Test
  fun `a clip with no effect list of one look stays on each clip`() =
    runBlocking<Unit> {
      val both = clip(::rising, Crt(), Brightness(0.1f))
      draw.graphOf(draw.composition(listOf(both, both))) shouldNotContain HOISTED
    }

  @Test
  fun `a track that opens on a gap stays on each clip`() =
    runBlocking<Unit> {
      val track = Track(listOf(clip(::rising, Crt()), clip(::falling, Crt())), start = 1.seconds)
      draw.graphOf(EditComposition(listOf(track))) shouldNotContain HOISTED
    }

  @Test
  fun `a source outside the tail format stays on each clip`() =
    runBlocking<Unit> {
      val full = draw.clipOf(WIDTH, HEIGHT, ::rising, listOf(Crt()), pixelFormat = "yuv444p")
      draw.graphOf(draw.composition(listOf(full, full))) shouldNotContain HOISTED
    }

  private fun clip(
    source: (Double) -> DoubleArray,
    vararg effects: EffectSpec,
  ): Clip = draw.clipOf(WIDTH, HEIGHT, source, effects.toList())

  private fun rising(fraction: Double): DoubleArray = doubleArrayOf(fraction, 0.5, 1.0 - fraction)

  private fun falling(fraction: Double): DoubleArray = doubleArrayOf(1.0 - fraction, 0.25, fraction)

  private companion object {
    const val WIDTH = 320
    const val HEIGHT = 180
    const val CLIP_SECONDS = 2
    const val HOISTED = "vhoist"
  }
}
