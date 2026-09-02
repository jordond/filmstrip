package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.ffmpeg.internal.FilterGraphBuilder
import dev.jordond.filmstrip.ffmpeg.internal.escapeFilterValue
import dev.jordond.filmstrip.ffmpeg.internal.render
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FilterGraphTextTest {
  @Test
  fun `writes a node with no arguments`() {
    FilterNode("hflip").render() shouldBe "hflip"
  }

  // A comma inside an argument would otherwise end the filter, and ffmpeg would read what follows
  // as another one. A colon separates one option from the next a level further in, and ffmpeg takes
  // both levels off, so the backslash that hides it has to survive the first pass: escaping the
  // colon once leaves the filter reading a bare separator.
  @Test
  fun `escapes the separators a value may contain`() {
    escapeFilterValue("between(t,1.0,4.0)") shouldBe "between(t\\,1.0\\,4.0)"
    escapeFilterValue("a:b") shouldBe "a\\\\:b"
    escapeFilterValue("[in]") shouldBe "\\[in\\]"
    escapeFilterValue("it's") shouldBe "it\\\\\\'s"
    escapeFilterValue("C:\\Users\\a") shouldBe "C\\\\:\\\\\\\\Users\\\\\\\\a"
  }

  @Test
  fun `joins a chain with commas`() {
    listOf(FilterNode("hflip"), FilterNode("vflip")).render() shouldBe "hflip,vflip"
  }

  @Test
  fun `writes chains in order`() {
    val graph = FilterGraphBuilder()
    graph.chain(listOf("0:v"), listOf(FilterNode("hflip")), "a")
    graph.chain(listOf("a"), listOf(FilterNode("vflip")), "b")

    graph.build("b") shouldBe "[0:v]hflip[a];[a]vflip[b]"
  }

  // Reading a label twice is not an error in ffmpeg: the parser matches it once and connects the
  // second reference to an unused input stream, at exit code 0, rendering the wrong thing.
  @Test
  fun `refuses a pad read twice`() {
    val graph = FilterGraphBuilder()
    graph.chain(listOf("0:v"), listOf(FilterNode("scale", "w" to "64", "h" to "64")), "v")
    graph.chain(listOf("v"), listOf(FilterNode("hflip")), "a")
    graph.chain(listOf("v"), listOf(FilterNode("vflip")), "b")

    assertFailsWith<IllegalArgumentException> { graph.build("a", "b") }
  }

  @Test
  fun `refuses a pad written twice`() {
    val graph = FilterGraphBuilder()
    graph.chain(listOf("0:v"), listOf(FilterNode("hflip")), "a")

    assertFailsWith<IllegalArgumentException> {
      graph.chain(listOf("1:v"), listOf(FilterNode("vflip")), "a")
    }
  }

  @Test
  fun `refuses a pad nothing reads`() {
    val graph = FilterGraphBuilder()
    graph.chain(listOf("0:v"), listOf(FilterNode("hflip")), "a")
    graph.chain(listOf("1:v"), listOf(FilterNode("vflip")), "b")

    assertFailsWith<IllegalArgumentException> { graph.build("a") }
  }
}
