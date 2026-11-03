package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.ftyp
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.ipma
import dev.jordond.filmstrip.picture.pictureOrientationOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.test.fail

/**
 * Lays out the `meta`, `iprp` and `ipco` boxes of hand-built HEIFs in the ways a file may and checks that the drop and
 * [pictureOrientationOf] read the same boxes, so no result is handed over still turned.
 */
class HeifMetaTest {
  private val ispe = fullBox("ispe", 0, 0, ByteArray(8))
  private val irot = box("irot", byteArrayOf(1))
  private val agreeing = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, true))).nal()

  @Test
  fun `an ipco in a later iprp is read by neither the drop nor the reader`() {
    val input = laterIpco(listOf(1, 2))
    pictureOrientationOf(input) shouldBe PictureOrientation.Normal
    heifWithoutOrientation(input).untagged shouldBeSameInstanceAs input
  }

  @Test
  fun `the first of two ipcos is the one the drop and the reader take`() {
    val turnedFirst = twoIpcos(box("ipco", ispe, irot), box("ipco", ispe, ispe), listOf(1, 2))
    pictureOrientationOf(turnedFirst) shouldNotBe PictureOrientation.Normal
    val expected = twoIpcos(box("ipco", ispe, irot), box("ipco", ispe, ispe), listOf(1))
    heifWithoutOrientation(turnedFirst).dropped shouldBe expected
    val turnedSecond = twoIpcos(box("ipco", ispe, ispe), box("ipco", ispe, irot), listOf(1, 2))
    pictureOrientationOf(turnedSecond) shouldBe PictureOrientation.Normal
    heifWithoutOrientation(turnedSecond).untagged shouldBeSameInstanceAs turnedSecond
  }

  @Test
  fun `only the first meta box is read`() {
    val turned = meta(box("iprp", box("ipco", ispe, irot), ipma(listOf(1 to listOf(1, 2)))))
    val dropped = meta(box("iprp", box("ipco", ispe, irot), ipma(listOf(1 to listOf(1)))))
    val turnedFirst = file(turned, dropped)
    pictureOrientationOf(turnedFirst) shouldNotBe PictureOrientation.Normal
    heifWithoutOrientation(turnedFirst).dropped shouldBe file(dropped, dropped)
    val turnedSecond = file(dropped, turned)
    pictureOrientationOf(turnedSecond) shouldBe PictureOrientation.Normal
    heifWithoutOrientation(turnedSecond).untagged shouldBeSameInstanceAs turnedSecond
  }

  @Test
  fun `no result reads turned and a turned input is never untagged`() {
    for (whole in corpus()) {
      for (length in 0..whole.size) {
        val input = whole.copyOf(length)
        val turned = pictureOrientationOf(input) != PictureOrientation.Normal
        for (result in listOf(heifWithoutOrientation(input), heifWithExplicitCodestreamColor(input))) {
          if (turned && result is UnturnedHeif.Untagged) fail("A turned file of $length bytes was untagged")
          if (result is UnturnedHeif.Handed) result.bytes.shouldReadNormal(length)
        }
        for (color in listOf(RestatedColor(6, 1), RestatedColor(0, 1))) {
          when (val result = heifWithRestatedColor(input) { _, _, _ -> color }) {
            is RestatedHeif.Restated -> result.bytes.shouldReadNormal(length)
            is RestatedHeif.Unrestated -> result.bytes.shouldReadNormal(length)
            is UnturnedHeif.Refused -> Unit
          }
        }
      }
    }
  }

  private fun ByteArray.shouldReadNormal(length: Int) {
    if (pictureOrientationOf(this) != PictureOrientation.Normal) fail("A result of a $length byte file reads turned")
  }

  // The hand-built layouts above and the HEIC shapes the drop tests use, each tagged on its primary item.
  private fun corpus(): List<ByteArray> {
    val transforms = listOf(irot)
    val shapes =
      listOf(
        TestHeic(listOf(agreeing), transforms = transforms),
        TestHeic(listOf(agreeing), transforms = transforms, ipmaVersion = 1, wideIndex = true),
        TestHeic(listOf(agreeing), transforms = transforms, large = true, splitIpma = true),
        TestHeic(listOf(agreeing), transforms = transforms, codedInIdat = true),
        TestHeic(listOf(agreeing), transforms = transforms, mdatFirst = true),
        TestHeic(listOf(agreeing), transforms = transforms, grid = true, transformed = listOf(1, 2)),
        TestHeic(listOf(agreeing), transforms = transforms, alphas = 1, transformed = listOf(1, ALPHA)),
      )
    val turned = meta(box("iprp", box("ipco", ispe, irot), ipma(listOf(1 to listOf(1, 2)))))
    val plain = meta(box("iprp", box("ipco", ispe), ipma(listOf(1 to listOf(1)))))
    return shapes.map { it.bytes() } +
      listOf(
        laterIpco(listOf(1, 2)),
        twoIpcos(box("ipco", ispe, irot), box("ipco", ispe, ispe), listOf(1, 2)),
        twoIpcos(box("ipco", ispe, ispe), box("ipco", ispe, irot), listOf(1, 2)),
        file(turned, plain),
        file(plain, turned),
      )
  }

  // A HEIF whose first iprp holds an empty ipma and whose second holds the ipco and item 1's indices.
  private fun laterIpco(indices: List<Int>): ByteArray {
    val later = box("iprp", box("ipco", ispe, irot), ipma(listOf(1 to indices)))
    return file(meta(box("iprp", ipma(emptyList())), later))
  }

  // A HEIF whose one iprp holds the first and second ipco and then item 1's indices.
  private fun twoIpcos(
    first: ByteArray,
    second: ByteArray,
    indices: List<Int>,
  ): ByteArray = file(meta(box("iprp", first, second, ipma(listOf(1 to indices)))))

  // An AVIF of an ftyp and then each of metas.
  private fun file(vararg metas: ByteArray): ByteArray =
    bytes {
      raw(ftyp("avif", "mif1"))
      metas.forEach { raw(it) }
    }

  // A meta box holding an hdlr, a pitm naming item 1 and then children.
  private fun meta(vararg children: ByteArray): ByteArray =
    fullBox("meta", 0, 0, fullBox("hdlr", 0, 0, ByteArray(21)), fullBox("pitm", 0, 0, bytes { u16(1) }), *children)

  private companion object {
    const val ALPHA = TestHeic.FIRST_ALPHA_ITEM
  }
}
