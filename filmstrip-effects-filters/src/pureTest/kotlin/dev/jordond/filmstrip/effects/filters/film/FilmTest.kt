package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.EffectStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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

    steps.map { it.name } shouldBe listOf("response", "blurAcross", "blurDown", "halation", "grain")
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
    halationOnly.map { it.name } shouldBe listOf("response", "blurAcross", "blurDown", "halation")
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
    sizes.getValue("blurDown").height shouldBe plan.heightOf(1080)
    sizes.getValue("halation").width shouldBe 1920
    sizes.getValue("grain").height shouldBe 1080
  }

  @Test
  fun `the fragments open on their version and carry the signal's constants`() {
    FilmShader.steps(Film()).forEach { it.fragment.startsWith("#version 300 es") shouldBe true }
    val grain = FilmShader.steps(Film()).last().fragment

    grain.contains("${GRAIN_CELL_SCALE}u") shouldBe true
    grain.contains("draw(${PASS_GRAIN_SECOND}u") shouldBe true
  }
}
