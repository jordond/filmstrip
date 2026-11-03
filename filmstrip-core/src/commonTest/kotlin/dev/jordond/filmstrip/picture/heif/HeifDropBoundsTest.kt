package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.picture.fixtures.asciiBytes
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.heif
import dev.jordond.filmstrip.picture.fixtures.ipma
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Drops the `irot` and `imir` of hand-built HEIFs at each bound on what the drop keeps and one past it, checking that a
 * file at the bound is dropped like a small one and a file past it is refused by name.
 */
class HeifDropBoundsTest {
  private val ispe = fullBox("ispe", 0, 0, ByteArray(8))
  private val irot = box("irot", byteArrayOf(1))
  private val imir = box("imir", byteArrayOf(0))
  private val agreeing = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, true))).nal()

  @Test
  fun `auxl sources that are not alpha drop up to the cap and refuse the file past it`() {
    val shape = { sources: Int, transformed: List<Int> ->
      TestHeic(
        listOf(agreeing),
        alphas = sources,
        auxiliaryType = asciiBytes(GAIN_MAP_URN) + ByteArray(1),
        transforms = listOf(irot, imir),
        transformed = transformed,
      ).bytes()
    }
    heifWithoutOrientation(shape(MAX_AUXL_SOURCES, listOf(1))).dropped shouldBe shape(MAX_AUXL_SOURCES, emptyList())
    heifWithoutOrientation(shape(MAX_AUXL_SOURCES + 1, listOf(1))).shouldBeRefused(TOO_MANY_AUXILIARY)
  }

  @Test
  fun `an untagged file passes at the auxl cap and is refused past it as too large to tell`() {
    val shape = { sources: Int ->
      TestHeic(
        listOf(agreeing),
        alphas = sources,
        auxiliaryType = asciiBytes(GAIN_MAP_URN) + ByteArray(1),
        transforms = listOf(irot, imir),
        transformed = emptyList(),
      ).bytes()
    }
    val atCap = shape(MAX_AUXL_SOURCES)
    heifWithoutOrientation(atCap).untagged shouldBeSameInstanceAs atCap
    heifWithoutOrientation(shape(MAX_AUXL_SOURCES + 1)).shouldBeRefused(TOO_MANY_AUXILIARY)
  }

  @Test
  fun `an untagged file passes at the ipma cap and is refused past it as too large to tell`() {
    val layout = { boxes: Int ->
      heif(listOf(ispe, irot), ipma(listOf(1 to listOf(1))), *Array(boxes - 1) { ipma(emptyList()) })
    }
    val atCap = layout(MAX_LISTED_BOXES)
    heifWithoutOrientation(atCap).untagged shouldBeSameInstanceAs atCap
    heifWithoutOrientation(layout(MAX_LISTED_BOXES + 1)).shouldBeRefused(TOO_MANY_LISTED)
  }

  @Test
  fun `an ipma listing 200000 unnamed items drops the primary item's irot as one listing 10 does`() {
    for (unnamed in listOf(10, 200_000)) {
      val layout = { indices: List<Int> ->
        val others = List(unnamed) { (2 + it) to listOf(1, 2) }
        heif(listOf(ispe, irot), ipma(listOf(1 to indices) + others, version = 1))
      }
      heifWithoutOrientation(layout(listOf(1, 2))).dropped shouldBe layout(listOf(1))
    }
  }

  @Test
  fun `ipma boxes drop up to the cap and refuse the file past it`() {
    val layout = { boxes: Int, indices: List<Int> ->
      heif(listOf(ispe, irot), ipma(listOf(1 to indices)), *Array(boxes - 1) { ipma(emptyList()) })
    }
    heifWithoutOrientation(layout(MAX_LISTED_BOXES, listOf(1, 2))).dropped shouldBe layout(MAX_LISTED_BOXES, listOf(1))
    heifWithoutOrientation(layout(MAX_LISTED_BOXES + 1, listOf(1, 2))).shouldBeRefused(TOO_MANY_LISTED)
  }

  @Test
  fun `top-level boxes drop up to the cap and refuse the file past it`() {
    val layout = { boxes: Int, indices: List<Int> ->
      val free = bytes { repeat(boxes - 2) { raw(box("free")) } }
      heif(listOf(ispe, irot), ipma(listOf(1 to indices))) + free
    }
    heifWithoutOrientation(layout(MAX_LISTED_BOXES, listOf(1, 2))).dropped shouldBe layout(MAX_LISTED_BOXES, listOf(1))
    heifWithoutOrientation(layout(MAX_LISTED_BOXES + 1, listOf(1, 2))).shouldBeRefused(TOO_MANY_LISTED)
  }

  private companion object {
    const val GAIN_MAP_URN = "urn:com:apple:photo:2020:aux:hdrgainmap"
  }
}
