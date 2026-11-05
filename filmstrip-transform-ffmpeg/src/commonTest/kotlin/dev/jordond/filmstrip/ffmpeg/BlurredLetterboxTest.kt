package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.lowering.coverBlurNodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.sigmaFor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.roundToInt
import kotlin.test.Test

// The three halves of a blurred letterbox, rendered as ffmpeg reads them. The sigma and the dim are
// read off the shared functions, so only gblur's own ceiling is a literal here.
class BlurredLetterboxTest {
  // The default radius lands between two whole sigmas at this size, so the node shows it rounded.
  @Test
  fun `the background covers the output and blurs at the fill's sigma`() {
    val fill = Fill.Blurred()

    coverBlurNodes(OUTPUT, fill).render() shouldBe
      "scale=w=1280:h=720:force_original_aspect_ratio=increase,crop=w=1280:h=720," +
      "gblur=sigma=${fill.sigmaFor(OUTPUT).roundToInt()}"
  }

  @Test
  fun `an undimmed background has no gain stage`() {
    coverBlurNodes(OUTPUT, Fill.Blurred(dim = 0f)).render() shouldNotContain "colorchannelmixer"
  }

  @Test
  fun `a dimmed background scales every color channel by the shared gain`() {
    val fill = Fill.Blurred(dim = 0.35f)
    val gain = fill.backgroundGain.toString()

    coverBlurNodes(OUTPUT, fill).last().render() shouldBe "colorchannelmixer=rr=$gain:gg=$gain:bb=$gain"
  }

  // gblur's own limit, not filmstrip's: it refuses a sigma past 1024.
  @Test
  fun `a sigma past gblur's ceiling is held to it`() {
    val fill = Fill.Blurred(radius = 2f)

    (fill.sigmaFor(OUTPUT) > GBLUR_CEILING) shouldBe true
    coverBlurNodes(OUTPUT, fill)[2].render() shouldBe "gblur=sigma=$GBLUR_CEILING"
  }

  private companion object {
    val OUTPUT = Size(1280, 720)
    const val GBLUR_CEILING = 1024
  }
}
