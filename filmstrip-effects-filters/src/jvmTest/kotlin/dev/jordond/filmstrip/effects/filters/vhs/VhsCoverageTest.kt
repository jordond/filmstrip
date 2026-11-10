package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class VhsCoverageTest {
  private val width = 1920
  private val reach = 60

  @Test
  fun `a frame with no clear column reads plain`() {
    coverageOf(NormalizedRect.Full, width, reach) shouldBe Coverage.Opaque
  }

  @Test
  fun `a bar under a pixel is no bar`() {
    coverageOf(NormalizedRect(0.0004f, 0f, 0.9996f, 1f), width, reach) shouldBe Coverage.Opaque
  }

  @Test
  fun `a frame with no known opaque part reads exactly everywhere`() {
    coverageOf(null, width, reach) shouldBe Coverage.Anywhere
  }

  @Test
  fun `a bar either side takes an even core around each edge of the picture`() {
    val picture = NormalizedRect(0.25f, 0f, 0.75f, 1f)
    val edges = coverageOf(picture, width, reach).shouldBeInstanceOf<Coverage.Edges>()

    edges.cores.map { it.from to it.to } shouldBe listOf(420 to 540, 1380 to 1500)
    edges.pieces(width).map { it.span.from to it.span.to } shouldBe
      listOf(0 to 420, 420 to 540, 540 to 1380, 1380 to 1500, 1500 to width)
    edges.pieces(width).map { it.core } shouldBe listOf(null, 0, null, 1, null)
  }

  @Test
  fun `one bar takes the one core`() {
    val edges = coverageOf(NormalizedRect(0.25f, 0f, 1f, 1f), width, reach).shouldBeInstanceOf<Coverage.Edges>()

    edges.cores.size shouldBe 1
  }

  @Test
  fun `cores that meet are one and a core across the frame reads exactly everywhere`() {
    val narrow = NormalizedRect(0.45f, 0f, 0.55f, 1f)
    coverageOf(narrow, width, reach).shouldBeInstanceOf<Coverage.Edges>().cores.size shouldBe 1
    coverageOf(narrow, width, width) shouldBe Coverage.Anywhere
  }

  @Test
  fun `an exact read on the whole frame has no premultiply and no strips`() {
    val names = names(vhsFragment(Vhs(), Size(WIDTH, HEIGHT), opaque = null))

    names shouldContain "geq"
    names shouldNotContain "hstack"
    names shouldNotContain "mergeplanes"
  }

  @Test
  fun `an opaque frame skips the premultiply and the divide`() {
    val names = names(vhsFragment(Vhs(), Size(WIDTH, HEIGHT)))

    names shouldNotContain "mergeplanes"
    names shouldNotContain "hstack"
    val barred = names(vhsFragment(Vhs(), Size(WIDTH, HEIGHT), NormalizedRect(0.25f, 0f, 0.75f, 1f)))
    names.count { it == "lut2" } shouldBe barred.count { it == "lut2" } - 2
  }

  @Test
  fun `a frame with bars lays the exact read over the strips of its plain tape`() {
    val names = names(vhsFragment(Vhs(), Size(WIDTH, HEIGHT), NormalizedRect(0.25f, 0f, 0.75f, 1f)))

    names shouldContain "hstack"
    names shouldContain "mergeplanes"
    names.count { it == "geq" } shouldBeGreaterThan 2
  }

  private fun names(fragment: dev.jordond.filmstrip.effect.FilterFragment) =
    fragment.branches.flatMap { branch -> branch.nodes.map { it.name } }

  private companion object {
    const val WIDTH = 1280
    const val HEIGHT = 720
  }
}
