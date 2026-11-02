package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.heifWithRestatedColour
import dev.jordond.filmstrip.media.heifWithoutOrientation
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Drops the primary item's `irot`, `imir` and `clap` associations from hand-built HEICs and AVIFs, comparing each
 * result byte for byte with the file built from scratch without them, so every box size, item location and other
 * association is checked.
 */
class HeifOrientationDropTest {
  private val irot = box("irot", byteArrayOf(1))
  private val imir = box("imir", byteArrayOf(0))
  private val turned = heifOrientationOf(1, 0)
  private val agreeing = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, true))).nal()
  private val silent = SpsShape().nal()

  private fun tagged(
    sps: ByteArray = agreeing,
    transforms: List<ByteArray> = listOf(irot, imir),
    transformed: List<Int> = listOf(1),
    shape: (List<ByteArray>, List<Int>) -> TestHeic = { properties, items ->
      TestHeic(listOf(sps), transforms = properties, transformed = items)
    },
  ): Pair<ByteArray, ByteArray> = shape(transforms, transformed).bytes() to shape(transforms, transformed - 1).bytes()

  @Test
  fun `only the primary item's irot and imir associations go on every ipma shape`() {
    for (version in 0..1) {
      for (wide in listOf(false, true)) {
        val (input, expected) =
          tagged { p, i ->
            TestHeic(listOf(agreeing), transforms = p, transformed = i, ipmaVersion = version, wideIndex = wide)
          }
        heifWithExplicitCodestreamColour(input).dropped shouldBe expected
      }
    }
  }

  @Test
  fun `the original reads its tag and the result reads Normal`() {
    val (input, _) = tagged()
    pictureOrientationOf(input) shouldBe turned
    turned shouldNotBe PictureOrientation.Normal
    pictureOrientationOf(heifWithExplicitCodestreamColour(input).dropped) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `a lone irot or imir goes too`() {
    for (transform in listOf(irot, imir)) {
      val (input, expected) = tagged(transforms = listOf(transform))
      heifWithExplicitCodestreamColour(input).dropped shouldBe expected
    }
  }

  @Test
  fun `item locations move in mdat and idat and through 64-bit sizes`() {
    val shapes =
      listOf<(List<ByteArray>, List<Int>) -> TestHeic>(
        { p, i -> TestHeic(listOf(agreeing), transforms = p, transformed = i, large = true) },
        { p, i -> TestHeic(listOf(agreeing), transforms = p, transformed = i, baseOffset = true, ilocVersion = 2) },
        { p, i -> TestHeic(listOf(agreeing), transforms = p, transformed = i, codedInIdat = true) },
        { p, i -> TestHeic(listOf(agreeing), transforms = p, transformed = i, splitExtents = true, ilocVersion = 0) },
        { p, i -> TestHeic(listOf(agreeing), transforms = p, transformed = i, mdatFirst = true) },
      )
    for (shape in shapes) {
      val (input, expected) = tagged(shape = shape)
      heifWithExplicitCodestreamColour(input).dropped shouldBe expected
    }
  }

  @Test
  fun `a grid keeps the irot its tiles list`() {
    val shape = { p: List<ByteArray>, i: List<Int> ->
      TestHeic(listOf(agreeing), grid = true, large = true, transforms = p, transformed = i)
    }
    val (input, expected) = tagged(transformed = listOf(1, 2, 3), shape = shape)
    heifWithExplicitCodestreamColour(input).dropped shouldBe expected
    pictureOrientationOf(expected) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `a thumbnail keeps its own irot and imir`() {
    val shape = { p: List<ByteArray>, i: List<Int> ->
      TestHeic(listOf(agreeing), thumbnailNclx = Cicp(1, 13, 1, true), transforms = p, transformed = i)
    }
    val (input, expected) = tagged(transformed = listOf(1, TestHeic.THUMBNAIL_ITEM), shape = shape)
    heifWithExplicitCodestreamColour(input).dropped shouldBe expected
  }

  @Test
  fun `the colour is stated in the same pass`() {
    val (input, untagged) = tagged(sps = silent)
    val result = heifWithExplicitCodestreamColour(input).dropped
    result shouldBe heifWithExplicitCodestreamColour(untagged).handed
    result shouldNotBe untagged
    pictureOrientationOf(result) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `an AVIF loses its irot and imir with its sequence header stated`() {
    val shape = { p: List<ByteArray>, i: List<Int> ->
      TestHeic(
        emptyList(),
        itemType = "av01",
        configuration = box("av1C", byteArrayOf(0x81.toByte(), 0x0D, 0x0C, 0)),
        coded = SequenceShape().obu() + STAND_IN_FRAME,
        transforms = p,
        transformed = i,
      )
    }
    val (input, untagged) = tagged(shape = shape)
    pictureOrientationOf(input) shouldBe turned
    val result = heifWithExplicitCodestreamColour(input).dropped
    result shouldBe heifWithExplicitCodestreamColour(untagged).handed
    pictureOrientationOf(result) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `a restate drops them whether it applies or not`() {
    val (input, untagged) = tagged()
    val applied = { _: Int, _: Boolean, _: Any -> RestatedColour(6, 1) }
    heifWithRestatedColour(input, applied).restated shouldBe heifWithRestatedColour(untagged, applied).restated
    val stated = { _: Int, _: Boolean, _: Any -> RestatedColour(1, 1) }
    heifWithRestatedColour(untagged, stated).restated shouldBeSameInstanceAs untagged
    heifWithRestatedColour(input, stated).restated shouldBe untagged
    val refused = { _: Int, _: Boolean, _: Any -> RestatedColour(0, 1) }
    heifWithRestatedColour(untagged, refused).unrestated shouldBeSameInstanceAs untagged
    heifWithRestatedColour(input, refused).unrestated shouldBe untagged
  }

  @Test
  fun `a refused drop is never restated`() {
    val input = TestHeic(listOf(silent), trailing = "moov", transforms = listOf(irot, imir)).bytes()
    heifWithRestatedColour(input) { _, _, _ -> error("restate was called") }.shouldBeRefused(SEQUENCE)
    heifWithExplicitCodestreamColour(input).shouldBeRefused(SEQUENCE)
  }

  @Test
  fun `a file with nothing to drop or state is given back as it was`() {
    val untagged = TestHeic(listOf(agreeing)).bytes()
    heifWithExplicitCodestreamColour(untagged).untagged shouldBeSameInstanceAs untagged
    val (onlyThumbnail, _) =
      tagged(transformed = listOf(TestHeic.THUMBNAIL_ITEM)) { p, i ->
        TestHeic(listOf(agreeing), thumbnailNclx = Cicp(1, 13, 1, true), transforms = p, transformed = i)
      }
    heifWithExplicitCodestreamColour(onlyThumbnail).untagged shouldBeSameInstanceAs onlyThumbnail
  }

  @Test
  fun `a moov box refuses a tagged file and passes an untagged one`() {
    val input = TestHeic(listOf(agreeing), trailing = "moov", transforms = listOf(irot, imir)).bytes()
    heifWithoutOrientation(input).shouldBeRefused(SEQUENCE)
    val untagged = TestHeic(listOf(agreeing), trailing = "moov").bytes()
    heifWithoutOrientation(untagged).untagged shouldBeSameInstanceAs untagged
    heifWithExplicitCodestreamColour(untagged).untagged shouldBeSameInstanceAs untagged
    val thumbnailOnly =
      TestHeic(
        listOf(agreeing),
        trailing = "moov",
        thumbnailNclx = Cicp(1, 13, 1, true),
        transforms = listOf(irot),
        transformed = listOf(TestHeic.THUMBNAIL_ITEM),
      ).bytes()
    heifWithoutOrientation(thumbnailOnly).untagged shouldBeSameInstanceAs thumbnailOnly
  }

  @Test
  fun `lying counts refuse the file`() {
    val (input, _) = tagged()
    val ipma = indexOf(input, "ipma")
    val entries = input[ipma + 7].toInt()
    val patches = listOf(ipma + 4 to 0xFF, ipma + 10 to 0x7F, ipma + 7 to entries + 1, ipma + 5 to 0x01)
    for ((at, value) in patches) {
      val lying = input.copyOf().also { it[at] = value.toByte() }
      heifWithExplicitCodestreamColour(lying).shouldBeInstanceOf<UnturnedHeif.Refused>()
      heifWithoutOrientation(lying).shouldBeInstanceOf<UnturnedHeif.Refused>()
    }
  }

  @Test
  fun `an alpha item folding the same way loses its irot and imir too`() {
    val (input, expected) = alphaPair(listOf(irot, imir), alphaTransforms = null)
    heifWithExplicitCodestreamColour(input).dropped shouldBe expected
    val primaryWay = listOf(rotation(1), mirror(0))
    val alphaWay = listOf(mirror(1), rotation(1))
    pictureOrientationOf(heifOriented(turns = 1, axis = 1, mirrorFirst = true)) shouldBe heifOrientationOf(1, 0)
    val (folded, unfolded) = alphaPair(primaryWay, alphaWay)
    heifWithExplicitCodestreamColour(folded).dropped shouldBe unfolded
    pictureOrientationOf(unfolded) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `an alpha item turned another way refuses the file`() {
    val unturned = TestHeic(listOf(agreeing), alphas = 1, transforms = listOf(irot, imir)).bytes()
    val (otherWay, _) = alphaPair(listOf(irot, imir), alphaTransforms = listOf(rotation(2)))
    for (input in listOf(unturned, otherWay)) {
      heifWithExplicitCodestreamColour(input).shouldBeRefused(ALPHA_TURNED_ANOTHER_WAY)
      heifWithoutOrientation(input).shouldBeRefused(ALPHA_TURNED_ANOTHER_WAY)
    }
  }

  @Test
  fun `a 64-bit ipma and two ipma boxes both shrink`() {
    val both = listOf(irot, imir)
    val shapes =
      listOf<(List<Int>) -> TestHeic>(
        { i -> TestHeic(listOf(agreeing), transforms = both, transformed = i, largeIpma = true) },
        { i -> TestHeic(listOf(agreeing), alphas = 1, transforms = both, transformed = i, splitIpma = true) },
      )
    for (shape in shapes) {
      heifWithExplicitCodestreamColour(shape(listOf(1, ALPHA)).bytes()).dropped shouldBe shape(emptyList()).bytes()
    }
  }

  @Test
  fun `an entry losing every association keeps a count of 0`() {
    val shape = { i: List<Int> ->
      TestHeic(listOf(agreeing), transforms = listOf(irot, imir), transformed = i, onlyTransforms = true)
    }
    heifWithoutOrientation(shape(listOf(1)).bytes()).dropped shouldBe shape(emptyList()).bytes()
  }

  @Test
  fun `associations keep their essential bits`() {
    val (input, expected) =
      tagged { p, i -> TestHeic(listOf(agreeing), transforms = p, transformed = i, nonEssential = setOf(1, 2, 3)) }
    heifWithExplicitCodestreamColour(input).dropped shouldBe expected
  }

  @Test
  fun `a primary item with two entries refuses the file`() {
    val input =
      TestHeic(
        listOf(agreeing),
        thumbnailNclx = Cicp(1, 13, 1, true),
        transforms = listOf(irot, imir),
        splitIpma = true,
      ).bytes()
    val second = indexOf(input, "ipma", after = indexOf(input, "ipma"))
    input[second + 9] shouldBe TestHeic.THUMBNAIL_ITEM.toByte()
    input[second + 9] = 1
    heifWithoutOrientation(input).shouldBeRefused(SECOND_ENTRY)
  }

  @Test
  fun `the primary item and its alpha items drop together up to the cap`() {
    val shape = { alphas: Int, i: List<Int> ->
      TestHeic(listOf(agreeing), alphas = alphas, transforms = listOf(irot, imir), transformed = i)
    }
    val most = MAX_DROPPED_ITEMS - 1
    val all = listOf(1) + List(most) { ALPHA + it }
    heifWithoutOrientation(shape(most, all).bytes()).dropped shouldBe shape(most, emptyList()).bytes()
    val past = shape(most + 1, all + (ALPHA + most)).bytes()
    heifWithoutOrientation(past).shouldBeRefused(TOO_MANY_ALPHAS)
    val untaggedPast = shape(most + 1, emptyList()).bytes()
    heifWithoutOrientation(untaggedPast).untagged shouldBeSameInstanceAs untaggedPast
  }

  @Test
  fun `an iscl after the irot refuses the file and one before it stays`() {
    val iscl = fullBox("iscl", 0, 0, ByteArray(8) { if (it % 4 == 1) 1 else 0 })
    val after = TestHeic(listOf(agreeing), transforms = listOf(irot, iscl)).bytes()
    heifWithoutOrientation(after).shouldBeRefused(SCALED_AFTER)
    val before = TestHeic(listOf(agreeing), transforms = listOf(iscl, irot)).bytes()
    val result = heifWithoutOrientation(before).dropped
    result.size shouldBe before.size - 1
    pictureOrientationOf(result) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `a clap before or after the irot is dropped with it and the crop still reads from the input`() {
    val clap = clap(64, 1, 32, 1, horizontalOffset = 8)
    for (order in listOf(listOf(clap, irot), listOf(irot, clap), listOf(clap))) {
      val input = TestHeic(listOf(agreeing), transforms = order).bytes()
      val result = heifWithoutOrientation(input).dropped
      result.size shouldBe input.size - order.size
      pictureOrientationOf(result) shouldBe PictureOrientation.Normal
      pictureCropOf(result) shouldBe null
      pictureCropOf(input) shouldNotBe null
    }
  }

  @Test
  fun `a clap that reads no crop refuses the file`() {
    val input = TestHeic(listOf(agreeing), transforms = listOf(clap(64, 0, 32, 1))).bytes()
    heifWithoutOrientation(input).shouldBeRefused(CROP_UNREAD)
  }

  @Test
  fun `an alpha item cropped another way than its primary item refuses the file`() {
    val primary = clap(Size(64, 32))
    val alpha = clap(Size(32, 64))
    val shape = { alphaTransforms: List<ByteArray>? ->
      TestHeic(
        listOf(agreeing),
        alphas = 1,
        transforms = listOf(primary),
        transformed = listOf(1, ALPHA),
        alphaTransforms = alphaTransforms,
      ).bytes()
    }
    heifWithoutOrientation(shape(listOf(alpha))).shouldBeRefused(ALPHA_CROPPED_ANOTHER_WAY)
    heifWithoutOrientation(shape(null)).shouldBeInstanceOf<UnturnedHeif.Dropped>()
  }

  @Test
  fun `an item whose data runs over ipma refuses the file`() {
    val input = TestHeic(listOf(agreeing), transforms = listOf(irot, imir), mdatFirst = true).bytes()
    val iloc = indexOf(input, "iloc")
    val exif = (iloc until input.size).first { at -> EXIF_ENTRY.indices.all { input[at + it] == EXIF_ENTRY[it] } }
    val offset = ByteView(input, input.size).u32(exif + EXIF_ENTRY.size)
    val length = input.size - offset
    val lengthAt = exif + EXIF_ENTRY.size + 4
    repeat(4) { input[lengthAt + it] = (length ushr (8 * (3 - it))).toByte() }
    heifWithoutOrientation(input).shouldBeRefused(IN_ITEM_DATA)
  }

  @Test
  fun `heifWithoutOrientation leaves the colour as it is`() {
    val (input, untagged) = tagged(sps = silent)
    heifWithoutOrientation(input).dropped shouldBe untagged
    heifWithExplicitCodestreamColour(untagged).handed shouldNotBe untagged
    heifWithoutOrientation(untagged).untagged shouldBeSameInstanceAs untagged
    val notHeif = ByteArray(64)
    heifWithoutOrientation(notHeif).untagged shouldBeSameInstanceAs notHeif
  }

  @Test
  fun `every truncation refuses the file or gives one that reads Normal`() {
    val (input, _) = tagged()
    for (length in input.indices) {
      val cut = input.copyOf(length)
      val result = shouldNotThrowAny { heifWithExplicitCodestreamColour(cut) }
      if (result is UnturnedHeif.Handed) pictureOrientationOf(result.bytes) shouldBe PictureOrientation.Normal
    }
  }

  // A file whose primary item and alpha item list their transforms, and the same file with neither listing them.
  private fun alphaPair(
    transforms: List<ByteArray>,
    alphaTransforms: List<ByteArray>?,
  ): Pair<ByteArray, ByteArray> {
    val shape = { i: List<Int> ->
      TestHeic(
        listOf(agreeing),
        alphas = 1,
        transforms = transforms,
        alphaTransforms = alphaTransforms,
        transformed = i,
      )
    }
    return shape(listOf(1, ALPHA)).bytes() to shape(emptyList()).bytes()
  }

  private fun rotation(turns: Int): ByteArray = box("irot", byteArrayOf(turns.toByte()))

  private fun mirror(axis: Int): ByteArray = box("imir", byteArrayOf(axis.toByte()))

  // Where the payload of the first box of type past after starts, past its size and type.
  private fun indexOf(
    bytes: ByteArray,
    type: String,
    after: Int = 0,
  ): Int {
    val name = asciiBytes(type)
    return (maxOf(4, after) until bytes.size - 4).first { at -> name.indices.all { bytes[at + it] == name[it] } } + 4
  }

  private companion object {
    const val ALPHA = TestHeic.FIRST_ALPHA_ITEM

    // The iloc entry of the Exif item ahead of its extent: item 8, method 0, data reference 0, one extent.
    val EXIF_ENTRY = byteArrayOf(0, TestHeic.EXIF_ITEM.toByte(), 0, 0, 0, 0, 0, 1)
  }
}
