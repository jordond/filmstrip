package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.ffmpeg.support.CRT_CHROMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.CRT_LUMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.CrtMiss
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.crtMissOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Reads a [Crt] against the signal where a source's format or colour could take it off.
 *
 * The fragment's conversions are written from 8 bit limited BT.709 codes, whatever a clip's chain hands it.
 */
class FfmpegCrtSourceMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a clip crt lands on the signal from a ten bit source`() =
    runBlocking<Unit> { assertClipScopeReadsSignal("yuv420p10le", "tv") }

  @Test
  fun `a clip crt on an sd clip draws over the corrected colour`() =
    runBlocking<Unit> {
      val crt = Crt()
      val filmed = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(crt), onSmpteC = true)
      val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt, onSmpteC = true)
      val composition = draw.composition(listOf(filmed))

      val frame = draw.planesAt(composition, 0)
      val base = draw.frames(draw.composition(listOf(plain)), Duration.ZERO, 1).single()

      assertLanded(crtMissOf(frame, base, crt))
      val graph = draw.graphOf(composition)
      graph shouldContain SD_HEAD
      withClue("the head runs ahead of the crt and the tags after it") {
        graph.indexOf(SD_HEAD) shouldBeLessThan graph.indexOf(CRT)
        graph.lastIndexOf(BT709_TAGS) shouldBeGreaterThan graph.lastIndexOf(CRT)
      }
    }

  @Test
  fun `a composition crt over an sd clip draws over the corrected colour`() =
    runBlocking<Unit> {
      val crt = Crt()
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt, onSmpteC = true)

      val frame = draw.planesAt(draw.composition(listOf(clip), listOf<EffectSpec>(crt)), 0)
      val base = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, 1).single()

      assertLanded(crtMissOf(frame, base, crt))
    }

  private suspend fun assertClipScopeReadsSignal(
    pixelFormat: String,
    range: String,
  ) {
    val crt = Crt()
    val filmed = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(crt), pixelFormat, range)
    val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt, pixelFormat = pixelFormat, range = range)

    val frame = draw.planesAt(draw.composition(listOf(filmed)), 0)
    val base = draw.frames(draw.composition(listOf(plain)), Duration.ZERO, 1).single()

    assertLanded(crtMissOf(frame, base, crt))
  }

  private fun assertLanded(miss: CrtMiss) {
    withClue("luma ${miss.luma}, chroma ${miss.chroma}, change ${miss.change}") {
      miss.change shouldBeGreaterThan CRT_LUMA_CODES * 2
      miss.luma shouldBeLessThan CRT_LUMA_CODES
      miss.chroma shouldBeLessThan CRT_CHROMA_CODES
    }
  }

  private fun rampAt(x: Double): DoubleArray = doubleArrayOf(0.25 + 0.45 * x, 0.65 - 0.3 * x, 0.35 + 0.2 * x)

  private companion object {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val SD_HEAD = "colorspace=ispace"
    const val CRT = "remap"
    const val BT709_TAGS = "setparams=color_primaries=bt709"
  }
}
