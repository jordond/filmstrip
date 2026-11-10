package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.effect.EffectStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * What a [Film] declares to the planner, and the chain it runs.
 */
class FilmTest {
  @Test
  fun `declares its place beneath overlays and that it needs SDR`() {
    val film = Film()

    film.stage shouldBe EffectStage.Composite
    film.beneathOverlays shouldBe true
    film.requiresSdr shouldBe true
  }

  @Test
  fun `is inert only with both controls at zero`() {
    Film(0f, 0f).isInert shouldBe true
    Film(0f, 0.5f).isInert shouldBe false
    Film(0.5f, 0f).isInert shouldBe false
  }

  @Test
  fun `compares by value`() {
    Film(0.25f, 0.75f) shouldBe Film(0.25f, 0.75f)
  }

  @Test
  fun `refuses a control out of range`() {
    shouldThrow<IllegalArgumentException> { Film(grain = 1.5f) }
    shouldThrow<IllegalArgumentException> { Film(halation = -0.5f) }
  }

  @Test
  fun `halation runs before grain and grain reads its output`() {
    val steps = FilmShader.steps(Film(0.5f, 0.5f))

    steps.map { it.name } shouldBe listOf("response", "blurAcross", "blurUpward", "halation", "grain")
    steps
      .last()
      .reads.values
      .single() shouldBe "halation"
  }

  @Test
  fun `a control at zero skips its pass`() {
    val grainOnly = FilmShader.steps(Film(0.5f, 0f))
    val halationOnly = FilmShader.steps(Film(0f, 0.5f))

    grainOnly.map { it.name } shouldBe listOf("grain")
    grainOnly
      .single()
      .reads.values
      .single() shouldBe FilmShader.INPUT
    halationOnly.map { it.name } shouldBe listOf("response", "blurAcross", "blurUpward", "halation")
  }

  @Test
  fun `the weave moves the frame the halation and the grain read`() {
    val steps = FilmShader.steps(Film.Super8)

    steps.map { it.name } shouldBe
      listOf("weave", "response", "blurAcross", "blurUpward", "halation", "grain")
    steps
      .first()
      .reads.values
      .single() shouldBe FilmShader.INPUT
    steps
      .single { it.name == "response" }
      .reads.values
      .single() shouldBe "weave"
    steps.single { it.name == "halation" }.reads.values shouldBe listOf("weave", "blurUpward")
    steps
      .last()
      .reads.values
      .single() shouldBe "halation"
  }

  @Test
  fun `a flicker dust or gate alone runs the grain step on the input`() {
    for (film in listOf(Film(0f, 0f, flicker = 0.5f), Film(0f, 0f, dust = 0.5f), Film(0f, 0f, gate = 0.5f))) {
      val steps = FilmShader.steps(film)

      steps.map { it.name } shouldBe listOf("grain")
      steps
        .single()
        .reads.values
        .single() shouldBe FilmShader.INPUT
      val uniforms = steps.single().uniformsAt(1.3, 640, 360)
      uniforms.getValue("uFlicker").single() shouldBe film.flicker
      uniforms.getValue("uDust").single() shouldBe film.dust
      uniforms.getValue("uGate").single() shouldBe film.gate
      uniforms.getValue("uStrength").single() shouldBe 0f
    }
    FilmShader.steps(Film(0f, 0f, weave = 0.5f)).map { it.name } shouldBe listOf("weave")
  }

  @Test
  fun `the weave and grain take the film's seed and the frame's tick`() {
    val seconds = 1.3
    val film = Film(weave = 0.5f, seed = 42)

    for (name in listOf("weave", "grain")) {
      val uniforms = FilmShader.steps(film).single { it.name == name }.uniformsAt(seconds, 640, 360)

      uniforms.getValue("uSeed").single() shouldBe 42f
      uniforms.getValue("uTick").single() shouldBe FilmSignal.grainTickAt(seconds).toFloat()
    }
  }

  @Test
  fun `every step reads only the input and earlier steps`() {
    val seen = mutableSetOf(FilmShader.INPUT)

    FilmShader.steps(Film()).forEach { step ->
      seen.containsAll(step.reads.values) shouldBe true
      seen += step.name
    }
  }

  @Test
  fun `the blur steps run at the plan's size and the rest at the frame's`() {
    val plan = FilmSignal.halationBlurPlan(1920)
    val sizes = FilmShader.steps(Film()).associate { it.name to it.targetAt(1920, 1080) }

    sizes.getValue("response").width shouldBe plan.width
    sizes.getValue("blurUpward").height shouldBe plan.heightOf(1080)
    sizes.getValue("halation").width shouldBe 1920
    sizes.getValue("grain").height shouldBe 1080
  }

  // At 1366 by 768 the plan's factor of 3 leaves 455 blurred columns for 1366 pixels and 256 rows for 768, so the gap
  // the chain once took up the frame, the column gap times the frame's aspect, lands 1/255.8 apart rather than one row.
  // The GPU halation reads moved there, and at any frame the factor divides, where both are one row, they did not.
  @Test
  fun `the halation blur steps one blurred row a tap up the frame`() {
    val plan = FilmSignal.halationBlurPlan(UNEVEN_WIDTH)
    val upward = FilmShader.steps(Film()).single { it.name == "blurUpward" }

    val gap = upward.uniformsAt(0.0, UNEVEN_WIDTH, UNEVEN_HEIGHT).getValue("uGap")

    gap.toList() shouldBe floatArrayOf(0f, 1f / plan.heightOf(UNEVEN_HEIGHT)).toList()
    val aspectGap = 1f / plan.width * UNEVEN_WIDTH / UNEVEN_HEIGHT
    gap[1] shouldNotBe aspectGap
    val across = FilmShader.steps(Film()).single { it.name == "blurAcross" }
    across.uniformsAt(0.0, UNEVEN_WIDTH, UNEVEN_HEIGHT).getValue("uGap").toList() shouldBe
      floatArrayOf(1f / plan.width, 0f).toList()
  }

  @Test
  fun `the halation plan blurs its own size at a factor of 1 with the same kernel`() {
    for (width in listOf(640, 1366, 1920, 2560, 3840)) {
      val plan = FilmSignal.halationBlurPlan(width)
      val small = BlurPlan.of(HALATION_SIGMA, plan.width)

      small.factor shouldBe 1
      small.width shouldBe plan.width
      small.weights.toList() shouldBe plan.weights.toList()
    }
  }

  @Test
  fun `the halation glow is blurPlane of the response on the blurred raster`() {
    val plan = FilmSignal.halationBlurPlan(UNEVEN_WIDTH)
    val height = plan.heightOf(UNEVEN_HEIGHT)
    // One blurred pixel per frame pixel is a frame the plan never boxes, so the glow's field is the response itself.
    val glow = HalationGlow.of(plan.width, height) { x, y -> codesAt(x, y, height) }
    val response = List(3) { channel -> responsePlane(plan.width, height, channel) }

    val expected =
      List(3) { channel ->
        blurPlane(response[channel], DoubleArray(plan.width * height) { 1.0 }, plan.width, height, HALATION_SIGMA)
      }

    for (y in 0 until height step 7) {
      for (x in 0 until plan.width step 11) {
        val read = glow.at(x, y)
        for (channel in 0 until 3) read[channel] shouldBe (expected[channel][y * plan.width + x] plusOrMinus 1e-9)
      }
    }
  }

  @Test
  fun `the fragments open on their version and carry the signal's constants`() {
    FilmShader.steps(Film()).forEach { it.fragment.startsWith("#version 300 es") shouldBe true }
    val grain = FilmShader.steps(Film()).last().fragment

    grain.contains("${GRAIN_CELL_SCALE}u") shouldBe true
    grain.contains("draw(${PASS_GRAIN_SECOND}u") shouldBe true
  }

  // A bright bar across the middle third and a ramp down the frame, so the response varies on both axes.
  private fun codesAt(
    x: Int,
    y: Int,
    height: Int,
  ): DoubleArray {
    val level = if (y in height / 3 until 2 * height / 3) 1.0 else 0.6 + 0.4 * x / UNEVEN_WIDTH
    return doubleArrayOf(level, level, level)
  }

  private fun responsePlane(
    width: Int,
    height: Int,
    channel: Int,
  ): DoubleArray =
    DoubleArray(width * height) {
      val codes = codesAt(it % width, it / width, height)
      FilmSignal.halationResponse(codes[0], codes[1], codes[2])[channel]
    }

  private companion object {
    const val UNEVEN_WIDTH = 1366
    const val UNEVEN_HEIGHT = 768
  }
}
