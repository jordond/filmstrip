package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * A single look on a frame that may be clear anywhere reads every pixel exactly, and writes the alpha it reads at the
 * shifted column, which is the picture's alpha. It did write none before, so a frame came out clear.
 */
class TapePictureAnywhereAlphaTest {
  @Test
  fun `a look on a frame that may be clear anywhere writes the alpha the frame has at the shifted column`() {
    val fragment = tapePictureFragment(Vhs(intensity = 0.9f), Size(WIDTH, HEIGHT), opaque = null)
    val reads =
      fragment.branches.flatMap { it.nodes }.filter {
        it.name == "geq" &&
          it.arguments.any { a -> a.key == "a" }
      }
    val alphas =
      reads.mapNotNull { node -> node.arguments.firstOrNull { it.key == "a" }?.value }.filter {
        "alpha(" in
          it
      }

    alphas.size shouldBe 1
    alphas.single() shouldContain "ld(7)"
  }

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
  }
}
