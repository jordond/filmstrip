package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.test.sampleOfCode
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * How [platedSamples] lays text over a plate by its glyphs, the step the browser and Android draw text through.
 *
 * Glyph bytes are written by hand here, so every blend is read against source over worked from the authored codes'
 * samples. An ordinary glyph texel is modelled the way a platform stores it, premultiplied on the eight-bit grid and
 * divided back out, and a color glyph texel holds a color far from the text's.
 */
class PlatedSamplesTest {
  @Test
  fun uncoveredTexelsHoldThePlateAndCoveredOnesTheText() {
    val samples = platedSamples(inkOf(TEXT, 0, FULL_COVERAGE), TEXT, PLATE)

    texel(samples, 0) shouldBe channelsOf(PLATE)
    texel(samples, 1) shouldBe channelsOf(TEXT)
  }

  @Test
  fun aPartlyCoveredTexelDrawsTheTextAtItsAlphaSourceOverThePlate() {
    for (textAlpha in TEXT_ALPHAS) {
      for (cover in COVERAGES) {
        for (plate in listOf(PLATE, null)) {
          val text = withAlpha(TEXT, textAlpha)
          assertDrawnSourceOver(text, codesOf(text), cover, plate)
        }
      }
    }
  }

  @Test
  fun aColorGlyphKeepsItsOwnColorAtTheTextAlphaSourceOverThePlate() {
    for (textAlpha in TEXT_ALPHAS) {
      for (cover in COVERAGES) {
        for (plate in listOf(PLATE, null)) assertDrawnSourceOver(withAlpha(TEXT, textAlpha), COLOR_GLYPH, cover, plate)
      }
    }
  }

  @Test
  fun textAtHalfAlphaFullyCoveredWeighsAsFullAlphaHalfCovered() {
    val halfAlpha = texel(platedSamples(inkOf(TEXT, FULL_COVERAGE), withAlpha(TEXT, HALF), plate = null), 0)
    val halfCovered = texel(platedSamples(inkOf(TEXT, HALF_COVERAGE), TEXT, plate = null), 0)

    halfAlpha[ALPHA] shouldBe halfCovered[ALPHA]
  }

  @Test
  fun textWithNoPlateKeepsItsColorAtItsCoverage() {
    val samples = platedSamples(inkOf(TEXT, 0, HALF_COVERAGE), TEXT, plate = null)

    texel(samples, 0) shouldBe listOf(0, 0, 0, 0)
    val half = texel(samples, 1)
    half.take(3) shouldBe channelsOf(TEXT).take(3)
    half[ALPHA] shouldBe sampleOfCode(HALF_COVERAGE)
  }

  // Every alpha a texel can hold, every code each channel can hold, through each way a platform rounds the round trip.
  @Test
  fun anOrdinaryTexelAtEveryAlphaReadsAsInk() {
    for (store in Store.entries) {
      for (code in 0..TOP_CODE) {
        val text = (OPAQUE shl ALPHA_SHIFT) or argbOf(code, TOP_CODE - code, code * SPREAD % (TOP_CODE + 1))
        val ink = codesOf(text)
        val glyphs = glyphsOf((1..TOP_CODE).map { alpha -> ink.map { store.read(it, alpha) } + alpha })

        val samples = platedSamples(glyphs, text, plate = null)

        for (alpha in 1..TOP_CODE) {
          val read = texel(samples, alpha - 1).take(3)
          withClue("$store ${text.hex()} at alpha $alpha read as its own color") {
            read shouldBe ink.map(::sampleOfCode)
          }
        }
      }
    }
  }

  @Test
  fun aTexelAtTheBoundReadsAsInkAndOnePastItAsItsOwnColor() {
    for (alpha in BOUND_ALPHAS) {
      val reach = floor(glyphInkReach(alpha)).toInt()
      val ink = codesOf(TEXT)
      val atBound = listOf(ink[0] - reach) + ink.drop(1)
      val past = listOf(ink[0] - reach - 1) + ink.drop(1)

      val samples = platedSamples(glyphsOf(listOf(atBound + alpha, past + alpha)), TEXT, plate = null)

      withClue("the texel at the bound at alpha $alpha") { texel(samples, 0).take(3) shouldBe ink.map(::sampleOfCode) }
      withClue("the texel past the bound at alpha $alpha") {
        texel(samples, 1).take(3) shouldBe past.map(::sampleOfCode)
      }
    }
  }

  @Test
  fun samplesTheCallerAllocatesAreFilledWhateverTheyHeld() {
    val glyphs = inkOf(TEXT, 0, HALF_COVERAGE, FULL_COVERAGE)
    val held = ShortArray(glyphs.size) { STALE }

    val samples = platedSamples(glyphs, TEXT, plate = null, samples = held)

    samples shouldBeSameInstanceAs held
    samples shouldBe platedSamples(glyphs, TEXT, plate = null)
    assertFailsWith<IllegalArgumentException> { platedSamples(glyphs, TEXT, PLATE, ShortArray(glyphs.size - 1)) }
  }

  private fun assertDrawnSourceOver(
    text: Int,
    glyph: List<Int>,
    cover: Int,
    plate: Int?,
  ) {
    val over = sampleOfCode(codeOf(text, ALPHA_SHIFT)).toFloat() / TOP_SAMPLE * cover / TOP_CODE
    val under = (plate?.let { sampleOfCode(codeOf(it, ALPHA_SHIFT)).toFloat() / TOP_SAMPLE } ?: 0f) * (1 - over)

    val drawn = texel(platedSamples(glyphsOf(listOf(glyph + cover)), text, plate), 0)

    val glyphChannels = glyph.map(::sampleOfCode)
    val plateChannels = plate?.let(::channelsOf) ?: List(CHANNELS) { 0 }
    val alpha = over + under
    val blended = (0 until 3).map { (glyphChannels[it] * over + plateChannels[it] * under) / alpha }
    val case = "${text.hex()} text with glyph $glyph at coverage $cover over ${plate?.hex()}"
    (0 until 3).forEach {
      withClue("channel $it of $drawn, $case") {
        drawn[it].toFloat() shouldBe (blended[it] plusOrMinus ONE_SAMPLE)
      }
    }
    withClue("the alpha of $drawn, $case") {
      drawn[ALPHA].toFloat() shouldBe (alpha * TOP_SAMPLE plusOrMinus ONE_SAMPLE)
    }
  }

  private fun inkOf(
    text: Int,
    vararg coverages: Int,
  ): ByteArray = glyphsOf(coverages.map { codesOf(text) + it })

  private fun glyphsOf(texels: List<List<Int>>): ByteArray =
    ByteArray(texels.size * CHANNELS) { texels[it / CHANNELS][it % CHANNELS].toByte() }

  private fun texel(
    samples: ShortArray,
    index: Int,
  ): List<Int> = List(CHANNELS) { samples[index * CHANNELS + it].toInt() and SAMPLE_MASK }

  private fun channelsOf(argb: Int): List<Int> = SHIFTS.map { sampleOfCode(codeOf(argb, it)) }

  private fun codesOf(argb: Int): List<Int> = SHIFTS.take(3).map { codeOf(argb, it) }

  private fun codeOf(
    argb: Int,
    shift: Int,
  ): Int = (argb ushr shift) and BYTE

  private fun argbOf(
    red: Int,
    green: Int,
    blue: Int,
  ): Int = (red shl SHIFTS[0]) or (green shl SHIFTS[1]) or blue

  private fun withAlpha(
    argb: Int,
    alpha: Int,
  ): Int = (alpha shl ALPHA_SHIFT) or (argb and RGB_MASK)

  private fun Int.hex(): String = toUInt().toString(16)

  // How a platform premultiplies a code on the eight-bit grid and divides it back out, clamped to the top code.
  private enum class Store {
    RoundedBothWays,
    BlitThenRounded,
    RoundedThenTruncated,
    BlitThenTruncated,
    ;

    fun read(
      code: Int,
      alpha: Int,
    ): Int {
      val premultiplied =
        when (this) {
          RoundedBothWays, RoundedThenTruncated -> (code.toDouble() * alpha / TOP_CODE).roundToInt()
          // A blitter scales by the coverage plus one over 256 and drops the remainder.
          BlitThenRounded, BlitThenTruncated -> code * (alpha + 1) shr 8
        }
      val divided = premultiplied.toDouble() * TOP_CODE / alpha
      val read =
        when (this) {
          RoundedBothWays, BlitThenRounded -> divided.roundToInt()
          RoundedThenTruncated, BlitThenTruncated -> floor(divided).toInt()
        }
      return minOf(read, TOP_CODE)
    }
  }

  private companion object {
    const val TEXT = 0xFFF0C030.toInt()
    const val PLATE = 0x40102080
    const val FULL_COVERAGE = 0xFF
    const val HALF_COVERAGE = 0x80
    const val HALF = 0x80
    const val OPAQUE = 0xFF
    const val STALE: Short = 0x1234

    // A color glyph's own color, every channel far from the text's.
    val COLOR_GLYPH = listOf(0x20, 0x30, 0xD0)

    // Text alphas and coverages apart from each other and from the ends, so a dropped text alpha moves the blend.
    val TEXT_ALPHAS = listOf(0xFF, 0x80, 0x40)
    val COVERAGES = listOf(0x40, 0x80, 0xC0)

    // Alphas in the middle of the range, where the bound is a few codes wide, and full alpha, where it is narrowest.
    val BOUND_ALPHAS = listOf(0x40, 0x80, 0xC0, 0xFF)

    // Steps the blue channel through every code out of step with red and green.
    const val SPREAD = 97

    const val CHANNELS = 4
    const val ALPHA = 3
    const val BYTE = 0xFF
    const val RGB_MASK = 0xFFFFFF
    const val ONE_SAMPLE = 1f
    const val ALPHA_SHIFT = 24
    val SHIFTS = listOf(16, 8, 0, ALPHA_SHIFT)
  }
}
