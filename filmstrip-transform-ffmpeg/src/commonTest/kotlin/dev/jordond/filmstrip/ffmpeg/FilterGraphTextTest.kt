package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.ffmpeg.internal.FilterGraphBuilder
import dev.jordond.filmstrip.ffmpeg.internal.escapeFilterValue
import dev.jordond.filmstrip.ffmpeg.internal.render
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
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

  @Test
  fun `expands a plane lanes node into a lane per plane and a merge`() {
    val graph = FilterGraphBuilder()
    graph.chain(
      listOf("in"),
      listOf(
        FilterNode("format", "pix_fmts" to "yuv444p16le"),
        PlaneLanes.node(listOf("y", "u", "v"), FLOAT_RGB),
        FilterNode("hflip"),
      ),
      "out",
    )

    graph.build("out") shouldBe
      "[in]format=pix_fmts=yuv444p16le,format=pix_fmts=yuv444p16le,setrange=range=limited,extractplanes=planes=y+u+v" +
      "[outlane0y][outlane0u][outlane0v];" +
      "[outlane0y]format=pix_fmts=grayf32le[outlane0yf];" +
      "[outlane0u]format=pix_fmts=grayf32le[outlane0uf];" +
      "[outlane0v]format=pix_fmts=grayf32le[outlane0vf];" +
      "[outlane0yf][outlane0uf][outlane0vf]mergeplanes=map0s=0:map0p=0:map1s=1:map1p=0:map2s=2:map2p=0:" +
      "format=gbrpf32le,setrange=range=limited,hflip[out]"
  }

  // extractplanes writes red, green, blue and alpha in that order, and gbrapf32le stores green, blue, red and alpha, so
  // the merge reads each lane by the slot its plane owns.
  @Test
  fun `places each plane in its slot whatever order the node names them in`() {
    fun rendered(planes: List<String>): String =
      FilterGraphBuilder()
        .apply { chain(listOf("0:v"), listOf(PlaneLanes.node(planes, "gbrapf32le")), "a") }
        .build("a")

    val text = rendered(listOf("r", "g", "b", "a"))

    text shouldBe rendered(listOf("g", "b", "r", "a"))
    text shouldStartWith
      "[0:v]format=pix_fmts=gbrap16le,setrange=range=limited,extractplanes=planes=r+g+b+a" +
      "[alane0r][alane0g][alane0b][alane0a];"
    text shouldContain "[alane0a]format=pix_fmts=grayf32le[alane0af]"
    text shouldContain "[alane0gf][alane0bf][alane0rf][alane0af]mergeplanes="
    text shouldContain "map3s=3:map3p=0:format=gbrapf32le,setrange=range=limited[a]"
  }

  @Test
  fun `places Y'CbCr planes in y, u and v order whatever order the node names them in`() {
    fun rendered(planes: List<String>): String =
      FilterGraphBuilder().apply { chain(listOf("0:v"), listOf(PlaneLanes.node(planes, FLOAT_RGB)), "a") }.build("a")

    val text = rendered(listOf("v", "y", "u"))

    text shouldBe rendered(listOf("y", "u", "v"))
    text shouldContain "extractplanes=planes=y+u+v[alane0y][alane0u][alane0v];"
    text shouldContain "[alane0yf][alane0uf][alane0vf]mergeplanes="
  }

  @Test
  fun `gives each plane lanes node in a chain pads of its own`() {
    val graph = FilterGraphBuilder()
    val lanes = PlaneLanes.node(listOf("y", "u", "v"), FLOAT_RGB)
    graph.chain(listOf("0:v"), listOf(lanes, FilterNode("hflip"), lanes), "a")
    graph.chain(listOf("1:v"), listOf(lanes), "b")

    val text = graph.build("a", "b")

    text shouldContain
      "format=gbrpf32le,setrange=range=limited,hflip,format=pix_fmts=yuv444p16le,setrange=range=limited," +
      "extractplanes=planes=y+u+v[alane1y]"
    text shouldContain "[blane0y]"
  }

  // The frame is pinned to the sixteen-bit planar format of the named planes' family before it is tagged and cut, with
  // alpha only when alpha is named, so extractplanes always reads one depth.
  @Test
  fun `pins the frame to the sixteen-bit format of the named planes before cutting it`() {
    fun cut(
      planes: List<String>,
      format: String,
    ): String =
      FilterGraphBuilder()
        .apply { chain(listOf("0:v"), listOf(PlaneLanes.node(planes, format)), "a") }
        .build("a")
        .substringBefore("extractplanes")

    cut(listOf("y", "u", "v"), FLOAT_RGB) shouldBe "[0:v]format=pix_fmts=yuv444p16le,setrange=range=limited,"
    cut(listOf("y", "u", "v", "a"), FLOAT_RGBA) shouldBe "[0:v]format=pix_fmts=yuva444p16le,setrange=range=limited,"
    cut(listOf("r", "g", "b"), FLOAT_RGB) shouldBe "[0:v]format=pix_fmts=gbrp16le,setrange=range=limited,"
    cut(listOf("r", "g", "b", "a"), FLOAT_RGBA) shouldBe "[0:v]format=pix_fmts=gbrap16le,setrange=range=limited,"
  }

  @Test
  fun `expands into every target plane lanes lists`() {
    PlaneLanes.TARGETS.forEach { target ->
      val lanes = PlaneLanes.node(target.slots, target.format)
      val text = FilterGraphBuilder().apply { chain(listOf("0:v"), listOf(lanes), "a") }

      text.build("a") shouldContain "format=${target.format},setrange=range=limited[a]"
    }
  }

  // Built raw, past the checks PlaneLanes.node runs, so the lowering's own check is what refuses it.
  @Test
  fun `refuses plane lanes into a format that is not planar float`() {
    assertFailsWith<IllegalArgumentException> {
      FilterGraphBuilder().chain(listOf("0:v"), listOf(rawLanes("r+g+b", "rgb48le")), "a")
    }
  }

  @Test
  fun `refuses plane lanes naming fewer planes than the format holds`() {
    assertFailsWith<IllegalArgumentException> {
      FilterGraphBuilder().chain(listOf("0:v"), listOf(PlaneLanes.node(listOf("y", "u"), FLOAT_RGB)), "a")
    }
  }

  @Test
  fun `refuses plane lanes mixing Y'CbCr and RGB planes`() {
    assertFailsWith<IllegalArgumentException> {
      FilterGraphBuilder().chain(listOf("0:v"), listOf(PlaneLanes.node(listOf("y", "g", "b"), FLOAT_RGB)), "a")
    }
  }

  @Test
  fun `refuses plane lanes naming alpha in place of a colour plane`() {
    listOf(listOf("y", "u", "a"), listOf("r", "g", "a")).forEach { planes ->
      assertFailsWith<IllegalArgumentException>("$planes into $FLOAT_RGB") {
        FilterGraphBuilder().chain(listOf("0:v"), listOf(PlaneLanes.node(planes, FLOAT_RGB)), "a")
      }
    }
  }

  @Test
  fun `refuses plane lanes leaving the alpha slot empty`() {
    assertFailsWith<IllegalArgumentException> {
      FilterGraphBuilder().chain(listOf("0:v"), listOf(PlaneLanes.node(listOf("r", "g", "b"), "gbrapf32le")), "a")
    }
  }

  // Built raw, past the checks PlaneLanes.node runs, so the lowering's own check is what refuses it.
  @Test
  fun `refuses plane lanes into a one plane format`() {
    assertFailsWith<IllegalArgumentException> {
      FilterGraphBuilder().chain(listOf("0:v"), listOf(rawLanes("y", "grayf32le")), "a")
    }
  }

  @Test
  fun `refuses plane lanes reading two pads at once`() {
    assertFailsWith<IllegalArgumentException> {
      FilterGraphBuilder().chain(listOf("0:v", "1:v"), listOf(PlaneLanes.node(listOf("y", "u", "v"), FLOAT_RGB)), "a")
    }
  }

  private fun rawLanes(
    planes: String,
    format: String,
  ): FilterNode = FilterNode(PlaneLanes.NAME, PlaneLanes.PLANES to planes, PlaneLanes.FORMAT to format)

  private companion object {
    const val FLOAT_RGB = "gbrpf32le"
    const val FLOAT_RGBA = "gbrapf32le"
  }
}
