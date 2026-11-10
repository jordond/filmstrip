package dev.jordond.filmstrip.effects.filters.pixel

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test

/**
 * What a [Pixel] declares to the planner, the ranges it holds, and what its shader carries from the signal.
 */
class PixelTest {
  @Test
  fun declaresItsPlaceBeneathOverlaysAndThatItNeedsSdr() {
    val pixel = Pixel()

    pixel.stage shouldBe EffectStage.Composite
    pixel.beneathOverlays shouldBe true
    pixel.requiresSdr shouldBe true
  }

  @Test
  fun isNeverInertSinceTheCellsAlwaysChangeTheFrame() {
    Pixel().isInert shouldBe false
    Pixel(cells = Pixel.MAX_CELLS).isInert shouldBe false
  }

  @Test
  fun comparesByValueWithItsPalette() {
    Pixel(72, 0, 0.5f, PixelPalette.GameBoy) shouldBe Pixel(72, 0, 0.5f, PixelPalette(PixelPalette.GameBoy.colors))
  }

  @Test
  fun refusesValuesOutsideTheirRanges() {
    shouldThrow<IllegalArgumentException> { Pixel(cells = Pixel.MIN_CELLS - 1) }
    shouldThrow<IllegalArgumentException> { Pixel(cells = Pixel.MAX_CELLS + 1) }
    shouldThrow<IllegalArgumentException> { Pixel(levels = 1) }
    shouldThrow<IllegalArgumentException> { Pixel(levels = Pixel.MAX_LEVELS + 1) }
    shouldThrow<IllegalArgumentException> { Pixel(dither = 1.5f) }
    shouldThrow<IllegalArgumentException> { Pixel(dither = -0.5f) }
  }

  @Test
  fun aPaletteHoldsTwoToSixteenColours() {
    shouldThrow<IllegalArgumentException> { PixelPalette(listOf(0x000000)) }
    shouldThrow<IllegalArgumentException> { PixelPalette(List(PixelPalette.MAX_COLORS + 1) { it }) }
    PixelPalette(List(PixelPalette.MAX_COLORS) { it }).colors.size shouldBe PixelPalette.MAX_COLORS
  }

  @Test
  fun aPaletteKeepsItsColoursWhenTheCallersListChanges() {
    val colors = mutableListOf(0x000000, 0xFFFFFF)
    val palette = PixelPalette(colors)
    val hash = palette.hashCode()

    colors.clear()

    palette.colors shouldBe listOf(0x000000, 0xFFFFFF)
    palette shouldBe PixelPalette(listOf(0x000000, 0xFFFFFF))
    palette.hashCode() shouldBe hash
  }

  @Test
  fun theBuilderAddsThePixelLookItWasGiven() {
    val builder = EffectsBuilder()

    builder.pixel(cells = 96, levels = 6, dither = 0.25f, palette = PixelPalette.Cga)

    builder.build() shouldContainExactly listOf(Pixel(96, 6, 0.25f, PixelPalette.Cga))
  }

  @Test
  fun theFragmentOpensOnItsVersionAndCarriesTheBayerTable() {
    val fragment = PixelShader.fragment(null)

    fragment shouldStartWith "#version 300 es"
    fragment shouldContain PixelSignal.bayer(3, 3).toString()
    fragment shouldContain PixelSignal.bayer(1, 2).toString()
  }

  @Test
  fun aPaletteFragmentCarriesEveryEntryAsCodes() {
    val fragment = PixelShader.fragment(PixelPalette.GameBoy)
    val levels = PixelShader.fragment(null)

    PixelSignal.codesOf(PixelPalette.GameBoy).forEach { fragment shouldContain it[1].toString() }
    levels shouldNotContain PixelSignal.codesOf(PixelPalette.GameBoy)[1][1].toString()
  }

  @Test
  fun theUniformsCarryTheGridOfTheFrameSize() {
    val pixel = Pixel(cells = 90, levels = 5, dither = 0.5f, palette = PixelPalette.Cga)
    val uniforms = PixelShader.uniforms(pixel, WIDTH, HEIGHT)

    uniforms.getValue("uGrid").toList() shouldBe
      listOf(PixelSignal.columns(WIDTH, HEIGHT, 90).toFloat(), 90f)
    uniforms.getValue("uSpread")[0] shouldBe asUniform(PixelSignal.paletteSpread(PixelPalette.Cga.colors.size))
    uniforms.getValue("uDither")[0] shouldBe 0.5f
  }

  @Test
  fun aPaletteHandsOnlyTheUniformsItsFragmentDeclares() {
    assertUniformsMatchTheFragment(Pixel(cells = 90, levels = 5, dither = 0.5f, palette = PixelPalette.Cga))
  }

  @Test
  fun levelsHandOnlyTheUniformsTheirFragmentDeclares() {
    assertUniformsMatchTheFragment(Pixel(cells = 90, levels = 5, dither = 0.5f))
  }

  // media3 only registers a uniform the fragment holds, and setting any other fails the first frame.
  private fun assertUniformsMatchTheFragment(pixel: Pixel) {
    val fragment = PixelShader.fragment(pixel.palette)
    val declared =
      Regex("""uniform (?:float|vec2) ([^;]+);""")
        .findAll(fragment)
        .flatMap { match -> match.groupValues[1].split(",").map(String::trim) }
        .toSet() - PixelShader.FLIP_V

    PixelShader.uniforms(pixel, WIDTH, HEIGHT).keys shouldBe declared
  }

  // Through an array, since toFloat alone keeps the whole double on js and a uniform only has 32 bits.
  private fun asUniform(value: Double): Float = floatArrayOf(value.toFloat())[0]
}

private const val WIDTH = 1366
private const val HEIGHT = 768
