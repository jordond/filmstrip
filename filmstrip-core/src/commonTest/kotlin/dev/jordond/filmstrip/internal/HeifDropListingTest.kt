package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.heifWithoutOrientation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Drops the `irot` and `imir` of hand-built HEIFs read through [heifListing], checking that the drop calls a file
 * tagged when its primary item lists an `imir` or an `irot` of anything but 0, even one [pictureOrientationOf] reads
 * as not turned, and refuses one whose primary item's listing is refused.
 */
class HeifDropListingTest {
  private val agreeing = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, true))).nal()

  @Test
  fun `an irot of 0 alone leaves the file untagged`() {
    for (trailing in listOf(null, "moov")) {
      val input = TestHeic(listOf(agreeing), transforms = listOf(irot(0)), trailing = trailing).bytes()
      pictureOrientationOf(input) shouldBe PictureOrientation.Normal
      heifWithoutOrientation(input).untagged shouldBeSameInstanceAs input
    }
  }

  @Test
  fun `turns and mirrors that fold back to normal still go`() {
    val folding = listOf(listOf(irot(2), irot(2)), listOf(irot(1), irot(3)), listOf(imir(0), imir(0)))
    for (transforms in folding) {
      val shape = { i: List<Int> -> TestHeic(listOf(agreeing), transforms = transforms, transformed = i) }
      val input = shape(listOf(1)).bytes()
      pictureOrientationOf(input) shouldBe PictureOrientation.Normal
      val result = heifWithoutOrientation(input).dropped
      result shouldBe shape(emptyList()).bytes()
      pictureOrientationOf(result) shouldBe PictureOrientation.Normal
      val sequence = TestHeic(listOf(agreeing), transforms = transforms, trailing = "moov").bytes()
      heifWithoutOrientation(sequence).shouldBeRefused(SEQUENCE)
    }
  }

  @Test
  fun `every irot and imir counts and every one goes`() {
    val shape = { i: List<Int> -> TestHeic(listOf(agreeing), transforms = listOf(irot(0), irot(1)), transformed = i) }
    val input = shape(listOf(1)).bytes()
    pictureOrientationOf(input) shouldBe heifOrientationOf(1, null)
    heifWithoutOrientation(input).dropped shouldBe shape(emptyList()).bytes()
  }

  @Test
  fun `an ipma above version 1 refuses the file as heifListing does`() {
    val input = heif(TURNED, ipma(ENTRY, version = 2))
    pictureOrientationOf(input) shouldBe PictureOrientation.Normal
    heifWithoutOrientation(input).shouldBeRefused(IPMA_ABOVE_VERSION_1)
    heifWithoutOrientation(heif(TURNED, ipma(ENTRY, version = 1))).dropped shouldBe heif(TURNED, ipma(DROPPED, 1))
  }

  @Test
  fun `an ispe above version 0 refuses the file as heifListing does`() {
    val ispeVersion1 = fullBox("ispe", 1, 0, bytes { u32(40).u32(30) })
    val input = heif(listOf(ispeVersion1, irot(1)), ipma(ENTRY))
    pictureOrientationOf(input) shouldBe PictureOrientation.Normal
    heifWithoutOrientation(input).shouldBeRefused(ISPE_ABOVE_VERSION_0)
  }

  @Test
  fun `a primary item missing from the first iprp refuses the file as heifListing does`() {
    val ipco = box("ipco", *TURNED.toTypedArray())
    val input = iprps(box("iprp", ipco, ipma(listOf(2 to listOf(1, 2)))), box("iprp", ipco, ipma(ENTRY)))
    pictureOrientationOf(input) shouldBe PictureOrientation.Normal
    heifWithoutOrientation(input).shouldBeRefused(UNLISTED)
  }

  @Test
  fun `an irot on a thumbnail alone is refused wherever heifListing refuses the primary item`() {
    val thumbnailOnly = listOf(1 to listOf(1), 2 to listOf(1, 2))
    val ispeVersion1 = fullBox("ispe", 1, 0, bytes { u32(40).u32(30) })
    val refusals =
      listOf(
        heif(TURNED, ipma(thumbnailOnly, version = 2)) to IPMA_ABOVE_VERSION_1,
        heif(listOf(ispeVersion1, irot(1)), ipma(thumbnailOnly)) to ISPE_ABOVE_VERSION_0,
        heif(TURNED, ipma(listOf(2 to listOf(1, 2)))) to UNLISTED,
      )
    for ((input, reason) in refusals) {
      pictureOrientationOf(input) shouldBe PictureOrientation.Normal
      heifWithoutOrientation(input).shouldBeRefused(reason)
    }
  }

  @Test
  fun `an ipma above version 1 after the primary item's entry refuses the file`() {
    val input = heif(TURNED, ipma(ENTRY, version = 1), ipma(listOf(2 to listOf(1)), version = 2))
    pictureOrientationOf(input) shouldBe PictureOrientation.Normal
    pictureExtentOf(input) shouldBe null
    heifWithoutOrientation(input).shouldBeRefused(IPMA_ABOVE_VERSION_1)
  }

  @Test
  fun `a refusal reports one sentence naming its reason`() {
    val input = heif(TURNED, ipma(ENTRY, version = 2))
    val refused = heifWithoutOrientation(input).shouldBeInstanceOf<UnturnedHeif.Refused>()
    refused.message shouldBe
      "The picture's HEIF irot, imir or clap could not be dropped before its decoder reads it, since an ipma box is " +
      "above version 1, so the decoder could crop or turn it another way than core."
  }

  @Test
  fun `a later entry listing only an irot of 0 leaves the file untagged`() {
    val input = heif(listOf(ispe(Size(40, 30)), irot(0)), ipma(listOf(1 to listOf(1), 1 to listOf(2))))
    heifWithoutOrientation(input).untagged shouldBeSameInstanceAs input
    val turned = heif(TURNED, ipma(listOf(1 to listOf(1), 1 to listOf(2))))
    heifWithoutOrientation(turned).shouldBeRefused(SECOND_ENTRY)
  }

  @Test
  fun `an ipma above version 1 leaves the file untagged when its ipco holds no irot or imir`() {
    val input = heif(listOf(ispe(Size(40, 30))), ipma(listOf(1 to listOf(1)), version = 2))
    heifWithoutOrientation(input).untagged shouldBeSameInstanceAs input
  }

  // A HEIF whose meta holds a pitm for item 1 and then each of iprps in order.
  private fun iprps(vararg iprps: ByteArray): ByteArray =
    ftyp("avif", "mif1") +
      fullBox("meta", 0, 0, fullBox("hdlr", 0, 0, ByteArray(21)), fullBox("pitm", 0, 0, bytes { u16(1) }), *iprps)

  private companion object {
    val TURNED = listOf(ispe(Size(40, 30)), irot(1))
    val ENTRY = listOf(1 to listOf(1, 2))
    val DROPPED = listOf(1 to listOf(1))
  }
}
