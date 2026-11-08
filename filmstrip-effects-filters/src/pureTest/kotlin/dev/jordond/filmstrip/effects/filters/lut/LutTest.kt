package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectStage
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(InternalFilmstripApi::class)
class LutTest {
  private val curved = CubeLut.parse(cubeText(5) { red, green, blue -> floatArrayOf(red * red, green, 1f - blue) })

  @Test
  fun zeroIntensityAndAnIdentityTableAreInert() {
    Lut(curved, 0f).isInert shouldBe true
    Lut(CubeLut.identity(5)).isInert shouldBe true
    Lut(curved, 0.01f).isInert shouldBe false
  }

  @Test
  fun anIntensityOutsideItsRangeIsRefused() {
    assertFailsWith<IllegalArgumentException> { Lut(curved, 1.01f) }
    assertFailsWith<IllegalArgumentException> { Lut(curved, Float.NaN) }
  }

  @Test
  fun specComparesByTableContentAndIntensity() {
    val other = CubeLut.parse(cubeText(5) { red, green, blue -> floatArrayOf(red * red, green, 1f - blue) })

    Lut(curved, 0.5f) shouldBe Lut(other, 0.5f)
    (Lut(curved, 0.5f) == Lut(other, 0.6f)) shouldBe false
    (Lut(curved) == Lut(CubeLut.identity(5))) shouldBe false
  }

  @Test
  fun specRunsOverTheFillUnderOverlaysInSdr() {
    val lut = Lut(curved)

    lut.stage shouldBe EffectStage.Composite
    lut.beneathOverlays shouldBe true
    lut.requiresSdr shouldBe true
    lut.id shouldBe Lut.ID
  }

  @Test
  fun atlasHoldsEveryEntryToSixteenBits() {
    val atlas = lutAtlas(curved)
    val resolution = 0.5f / 65535f + TOLERANCE

    for (blue in 0 until curved.size) {
      for (green in 0 until curved.size) {
        for (red in 0 until curved.size) {
          val want =
            curved.at(red / 4f, green / 4f, blue / 4f)
          val read = atlasEntry(atlas, red, green, blue)
          (0..2).forEach { channel -> read[channel] shouldBe (want[channel] plusOrMinus resolution) }
        }
      }
    }
  }

  @Test
  fun atlasHoldsAMidRangeEntryFinerThanEightBits() {
    val fine = CubeLut.parse(cubeText(2) { _, _, _ -> floatArrayOf(0.5012f, 0.25037f, 0.75003f) })

    val read = atlasEntry(lutAtlas(fine), 1, 0, 1)

    read[0] shouldBe (0.5012f plusOrMinus 1e-5f)
    read[1] shouldBe (0.25037f plusOrMinus 1e-5f)
    read[2] shouldBe (0.75003f plusOrMinus 1e-5f)
  }

  @Test
  fun atlasIsOpaque() {
    val bytes = lutAtlas(curved).bytes

    (3 until bytes.size step 4).all { bytes[it] == (-1).toByte() } shouldBe true
  }

  @Test
  fun atlasTilesOnANearSquareGrid() {
    lutAtlasColumns(33) shouldBe 6
    lutAtlasWidth(33) shouldBe 396
    lutAtlasHeight(33) shouldBe 198
    lutAtlasWidth(65) shouldBe 1170
    lutAtlasHeight(65) shouldBe 520
    lutAtlasWidth(CubeLut.MAX_SIZE) shouldBe 3096
    lutAtlasHeight(CubeLut.MAX_SIZE) shouldBe 1419
  }

  @Test
  fun atlasLimitNamesTheContextsMaxTextureSize() {
    lutAtlasLimit(65, 2048).shouldBeNull()
    lutAtlasLimit(65, 1170).shouldBeNull()
    lutAtlasLimit(65, 1169)!! shouldContain "MAX_TEXTURE_SIZE is 1169"
    lutAtlasLimit(CubeLut.MAX_SIZE, 2048)!! shouldContain "3096x1419"
  }

  @Test
  fun uniformsCarryTheAtlasLayout() {
    val atlas = lutAtlas(curved)
    val uniforms = LutShader.uniforms(atlas)

    uniforms.getValue("uLutSize")[0] shouldBe curved.size.toFloat()
    uniforms.getValue("uLutColumns")[0] shouldBe atlas.columns.toFloat()
  }

  private fun atlasEntry(
    atlas: LutAtlas,
    red: Int,
    green: Int,
    blue: Int,
  ): FloatArray {
    val x = (blue % atlas.columns) * atlas.size + red
    val y = (blue / atlas.columns) * atlas.size + green
    val high = (y * atlas.width + x) * 4
    val low = high + atlas.width / 2 * 4
    return FloatArray(3) { channel ->
      val upper = atlas.bytes[high + channel].toInt() and 0xFF
      val lower = atlas.bytes[low + channel].toInt() and 0xFF
      (upper * 256 + lower) / MAX_ENTRY
    }
  }
}
