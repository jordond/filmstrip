package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.fixtures.asciiBytes
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.pictureOrientationOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Drops the `irot` and `imir` of hand-built HEICs whose primary item has auxiliary items, checking that only alpha
 * items are dropped from, the same ones [heifAlpha] reads, and that the `iref` is read safely.
 */
class HeifAuxiliaryDropTest {
  private val irot = box("irot", byteArrayOf(1))
  private val imir = box("imir", byteArrayOf(0))
  private val otherWay = box("irot", byteArrayOf(2))
  private val agreeing = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, true))).nal()

  @Test
  fun `gain map depth and matte items keep their associations and never block the drop`() {
    val many = MAX_DROPPED_ITEMS + 2
    val auxiliary = List(many) { ALPHA + it }
    for (urn in listOf(GAIN_MAP_URN, DEPTH_URN, MATTE_URN)) {
      val shape = { i: List<Int> ->
        TestHeic(
          listOf(agreeing),
          alphas = many,
          auxiliaryType = asciiBytes(urn) + ByteArray(1),
          transforms = listOf(irot, imir),
          alphaTransforms = listOf(otherWay),
          transformed = i,
        ).bytes()
      }
      val input = shape(listOf(1) + auxiliary)
      heifWithoutOrientation(input).dropped shouldBe shape(auxiliary)
      heifAlpha(input).shouldBeNull()
    }
  }

  @Test
  fun `a gain map without an irot leaves the drop and the stated codestream together`() {
    val shape = { i: List<Int> ->
      TestHeic(
        listOf(SpsShape().nal()),
        alphas = 1,
        auxiliaryType = asciiBytes(GAIN_MAP_URN) + ByteArray(1),
        transforms = listOf(irot),
        transformed = i,
      ).bytes()
    }
    val input = shape(listOf(1))
    val result = heifWithExplicitCodestreamColor(input).dropped
    result shouldBe heifWithExplicitCodestreamColor(shape(emptyList())).handed
    result shouldNotBe input
    pictureOrientationOf(result) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `an iref of version 1 drops the alpha item too`() {
    val shape = { i: List<Int> ->
      TestHeic(
        listOf(agreeing),
        alphas = 1,
        irefVersion = 1,
        ipmaVersion = 1,
        transforms = listOf(irot, imir),
        transformed = i,
      ).bytes()
    }
    val input = shape(listOf(1, ALPHA))
    heifWithoutOrientation(input).dropped shouldBe shape(emptyList())
    heifAlpha(input)?.item shouldBe ALPHA.toLong()
  }

  @Test
  fun `a lying reference count refuses a tagged file and passes an untagged one`() {
    val tagged = TestHeic(listOf(agreeing), alphas = 1, transforms = listOf(irot, imir), transformed = listOf(1, ALPHA))
    val untagged = TestHeic(listOf(agreeing), alphas = 1)
    for (file in listOf(tagged, untagged)) {
      val lying = file.bytes()
      val auxl = indexOf(lying, "auxl")
      lying[auxl + 2] shouldBe 0
      lying[auxl + 3] shouldBe 1
      lying[auxl + 3] = 0x7F
      val result = heifWithoutOrientation(lying)
      if (file === tagged) result.shouldBeRefused(UNPARSED) else result.untagged shouldBeSameInstanceAs lying
    }
  }

  @Test
  fun `an incoming primary entry listing nothing`() {
    val alphaOnly =
      TestHeic(
        listOf(agreeing),
        alphas = 1,
        transforms = listOf(irot),
        transformed = listOf(ALPHA),
        onlyTransforms = true,
      ).bytes()
    heifWithoutOrientation(alphaOnly).shouldBeRefused(ALPHA_TURNED_ANOTHER_WAY)
    val thumbnailOnly =
      TestHeic(
        listOf(agreeing),
        thumbnailNclx = agreeingNclx,
        transforms = listOf(irot),
        transformed = listOf(TestHeic.THUMBNAIL_ITEM),
        onlyTransforms = true,
      ).bytes()
    heifWithoutOrientation(thumbnailOnly).untagged shouldBeSameInstanceAs thumbnailOnly
  }

  @Test
  fun `an incoming alpha entry listing nothing is no alpha item`() {
    val shape = { i: List<Int> ->
      TestHeic(listOf(agreeing), alphas = 1, emptyAlphaEntries = true, transforms = listOf(irot), transformed = i)
        .bytes()
    }
    val input = shape(listOf(1))
    heifWithoutOrientation(input).dropped shouldBe shape(emptyList())
    heifAlpha(input).shouldBeNull()
  }

  @Test
  fun `an auxC URN cut short or run on is no alpha type and one the box ends is`() {
    val shape = { type: ByteArray, i: List<Int> ->
      TestHeic(
        listOf(agreeing),
        alphas = 1,
        auxiliaryType = type,
        transforms = listOf(irot),
        alphaTransforms = listOf(otherWay),
        transformed = i,
      ).bytes()
    }
    val urn = asciiBytes(TestHeic.ALPHA_URN)
    for (type in listOf(urn.copyOf(urn.size - 6), urn + asciiBytes("x") + ByteArray(1))) {
      val input = shape(type, listOf(1, ALPHA))
      heifWithoutOrientation(input).dropped shouldBe shape(type, listOf(ALPHA))
      heifAlpha(input).shouldBeNull()
    }
    val ended = shape(urn, listOf(1, ALPHA))
    heifWithoutOrientation(ended).shouldBeRefused(ALPHA_TURNED_ANOTHER_WAY)
    heifAlpha(ended).shouldNotBeNull()
  }

  // Where the payload of the first box of type starts, past its size and type.
  private fun indexOf(
    bytes: ByteArray,
    type: String,
  ): Int {
    val name = asciiBytes(type)
    return (4 until bytes.size - 4).first { at -> name.indices.all { bytes[at + it] == name[it] } } + 4
  }

  private companion object {
    const val ALPHA = TestHeic.FIRST_ALPHA_ITEM
    const val GAIN_MAP_URN = "urn:com:apple:photo:2020:aux:hdrgainmap"
    const val DEPTH_URN = "urn:mpeg:hevc:2015:auxid:2"
    const val MATTE_URN = "urn:com:apple:photo:2019:aux:semanticskinmatte"
    val agreeingNclx = Cicp(1, 13, 1, true)
  }
}
