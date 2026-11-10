package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.spatial.SpatialBlurGraph
import dev.jordond.filmstrip.ffmpeg.internal.lowering.coverBlurNodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

// The background half of a blurred letterbox, rendered as ffmpeg reads them. The blur and the dim
// are read off the shared functions, so no figure here is a literal.
class BlurredLetterboxTest {
  @Test
  fun `the background covers the output and blurs by the fill's shared plan`() {
    val fill = Fill.Blurred(radius = 0.05f)
    val plan = fill.blurPlan(OUTPUT)
    val nodes = coverBlurNodes(OUTPUT, fill, PIXEL_FORMAT).render()

    nodes shouldContain
      "scale=w=1280:h=720:force_original_aspect_ratio=increase,crop=w=1280:h=720," +
      SpatialBlurGraph.boxedDown(plan, plan, OUTPUT, COVER_FORMAT).render() + "," +
      SpatialBlurGraph.blurredUp(plan, plan, OUTPUT, COVER_FORMAT, COLOR_PLANES).render()
  }

  @Test
  fun `the background is handed on in the tail's format`() {
    coverBlurNodes(OUTPUT, Fill.Blurred(), PIXEL_FORMAT).last().render() shouldBe "format=pix_fmts=$PIXEL_FORMAT"
  }

  @Test
  fun `an undimmed background has no gain stage`() {
    coverBlurNodes(OUTPUT, Fill.Blurred(dim = 0f), PIXEL_FORMAT).render() shouldNotContain "colorchannelmixer"
  }

  @Test
  fun `a dimmed background scales every color channel by the shared gain`() {
    val fill = Fill.Blurred(dim = 0.35f)
    val gain = fill.backgroundGain.toString()

    coverBlurNodes(OUTPUT, fill, PIXEL_FORMAT).dropLast(1).last().render() shouldBe
      "colorchannelmixer=rr=$gain:gg=$gain:bb=$gain"
  }

  private companion object {
    val OUTPUT = Size(1280, 720)
    const val PIXEL_FORMAT = "yuv420p"
    const val COVER_FORMAT = "yuva444p16le"
    const val COLOR_PLANES = 7
  }
}
