package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.ffmpeg.support.FULL
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_RATE
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.VhsFrames
import dev.jordond.filmstrip.ffmpeg.support.assertReadsSignal
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.ffmpeg.support.yccOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Reads a [Vhs] over an SD clip against the signal over the colour that clip displays.
 *
 * The tape's figures are all BT.709's, so an SD clip's head has to run first, whatever else the plan holds.
 */
class FfmpegVhsSdTest {
  private val draw = VhsFrames()

  @Test
  fun `the sd ramp sits further from its displayed colour than a read can tell`() {
    val cr = { rgb: DoubleArray -> yccOf(DoubleArray(rgb.size) { rgb[it] * FULL })[2] }

    abs(cr(displayedAt(MIDDLE)) - cr(sdRampAt(MIDDLE))) shouldBeGreaterThan VHS_CHROMA_CODES
  }

  @Test
  fun `a clip vhs on an sd clip beside a bt709 clip tapes the corrected colour`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val plain = draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::rampAt)
      val sd = draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::sdRampAt, listOf(vhs), onSmpteC = true)
      val composition = draw.composition(listOf(plain, sd))
      val frame = draw.frames(composition, Duration.ZERO, SECOND_CLIP_SLOT + 1).last()

      assertReadsSignal(frame, vhs, VHS_WIDTH, VHS_HEIGHT, SECOND_CLIP_SLOT, ::displayedAt)
      assertHeadBeforeTape(draw.graphOf(composition))
    }

  @Test
  fun `a composition vhs over an sd clip beside a bt709 clip tapes the corrected colour`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val plain = draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::rampAt)
      val sd = draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::sdRampAt, onSmpteC = true)
      val composition = draw.composition(listOf(sd, plain), listOf(vhs))
      val frame = draw.frames(composition, Duration.ZERO, READ_SLOT + 1).last()

      assertReadsSignal(frame, vhs, VHS_WIDTH, VHS_HEIGHT, READ_SLOT, ::displayedAt)
      assertHeadBeforeTape(draw.graphOf(composition))
    }

  @Test
  fun `a vhs on a lone sd clip tapes the corrected colour at both scopes`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      listOf(
        "clip" to
          draw.composition(
            listOf(draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::sdRampAt, listOf(vhs), onSmpteC = true)),
          ),
        "composition" to
          draw.composition(listOf(draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::sdRampAt, onSmpteC = true)), listOf(vhs)),
      ).forEach { (scope, composition) ->
        withClue("$scope scope") {
          val frame = draw.frames(composition, Duration.ZERO, READ_SLOT + 1).last()

          assertReadsSignal(frame, vhs, VHS_WIDTH, VHS_HEIGHT, READ_SLOT, ::displayedAt)
          assertHeadBeforeTape(draw.graphOf(composition))
        }
      }
    }

  @Test
  fun `a vhs that draws nothing leaves a lone sd clip its own tags`() =
    runBlocking<Unit> {
      val sd = draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::sdRampAt, onSmpteC = true)

      draw.graphOf(draw.composition(listOf(sd), listOf(Vhs(intensity = 0f)))) shouldNotContain SD_HEAD
    }

  private fun assertHeadBeforeTape(graph: String) {
    graph shouldContain SD_HEAD
    val head = graph.indexOf(SD_HEAD)
    head shouldBeLessThan graph.indexOf(TAPE)
    graph.lastIndexOf(BT709_TAGS) shouldBeGreaterThan graph.lastIndexOf(TAPE)
  }

  // Mid-range, with green holding the frame up, which is where SMPTE C and BT.709 are furthest apart.
  private fun sdRampAt(x: Double): DoubleArray = doubleArrayOf(RED, GREEN_LOW + x.coerceIn(0.0, 1.0) * GREEN_SPAN, BLUE)

  private fun displayedAt(x: Double): DoubleArray = sdSourceDisplayed(sdRampAt(x), SMPTE_C_PRIMARIES)

  private companion object {
    const val MIDDLE = 0.5
    const val RED = 0.15
    const val GREEN_LOW = 0.45
    const val GREEN_SPAN = 0.3
    const val BLUE = 0.2
    const val READ_SLOT = 3
    const val CLIP_SECONDS = 2
    const val SECOND_CLIP_SLOT = CLIP_SECONDS * VHS_RATE + READ_SLOT
    const val SD_HEAD = "colorspace=ispace"
    const val TAPE = "gblur"
    const val BT709_TAGS = "setparams=color_primaries=bt709"
  }
}
