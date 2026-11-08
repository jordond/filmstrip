package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.MAX_WALK_BLOCK
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.embeddedColor
import dev.jordond.filmstrip.picture.fixtures.associations
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.ftyp
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.heif
import dev.jordond.filmstrip.picture.fixtures.heifItems
import dev.jordond.filmstrip.picture.fixtures.ipma
import dev.jordond.filmstrip.picture.heifPrimaryIsTiled
import dev.jordond.filmstrip.picture.pictureExtentOf
import dev.jordond.filmstrip.picture.pictureOrientationOf
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Which of an AVIF or HEIC's property associations its size and orientation are read from. libheif reads only the
// first ipma entry for an item, across every ipma box in order, and sizes it from the last ispe that entry lists.
class HeifListingTest {
  @Test
  fun onlyTheFirstEntryForTheItemCountsInOneIpmaOrAcrossTwo() =
    runTest {
      val properties = listOf(ispe(FRAME), irot(1), clap(Size(10, 10)))
      val first = 1 to listOf(1, 2)
      val later = 1 to listOf(2, 3)
      val files =
        mapOf(
          "one ipma" to heif(properties, ipma(listOf(first, later))),
          "two ipma boxes" to heif(properties, ipma(listOf(first)), ipma(listOf(later))),
          "another item first" to heif(properties, ipma(listOf(2 to listOf(2, 3), first, later))),
        )
      for ((name, file) in files) {
        withClue(name) { pictureOrientationOf(file) shouldBe heifOrientationOf(1, null) }
        withClue(name) { pictureExtentOf(file) shouldBe FRAME }
        withClue("$name fetched") { ranged(file) shouldBe FRAME }
      }
      val empty = heif(properties, ipma(listOf(1 to emptyList())), ipma(listOf(first)))
      assertRefused(empty, "first ipma lists the item with no associations")
    }

  @Test
  fun theLastIspeTheItemListsSetsItsSize() =
    runTest {
      val sized =
        listOf(
          "two ispe" to listOf(ispe(FRAME), ispe(Size(20, 10))) to Size(20, 10),
          "ispe after a clap" to listOf(ispe(Size(10, 10)), clap(Size(20, 10)), ispe(FRAME)) to Size(20, 10),
          "first ispe past an Int" to listOf(ispe(Int.MAX_VALUE + 1L, 30), ispe(FRAME)) to FRAME,
          "last ispe past an Int" to listOf(ispe(FRAME), ispe(Int.MAX_VALUE + 1L, 30)) to null,
          "first ispe of zero width" to listOf(ispe(0, 30), ispe(FRAME)) to null,
          "last ispe of zero height" to listOf(ispe(FRAME), ispe(40, 0)) to null,
        )
      for ((listing, size) in sized) {
        val (name, properties) = listing
        val file = heifItems(properties)

        withClue(name) { pictureExtentOf(file) shouldBe size }
        withClue("$name fetched") { ranged(file) shouldBe size }
      }
    }

  // libheif's decode crops each clap out of the picture the one ahead of it left, at the offsets it gives, and clamps
  // one that runs past it. Its size read reports the clap's own size and never reads the offsets. So the two disagree
  // on a clap that does not fit its frame, and on one its offsets push past the frame, such as a 20 by 30 clap offset
  // 15 to the right in a 40 by 30 frame, which decodes 15 wide and reads as 20. The sizes here are the size read's.
  @Test
  fun eachCleanApertureHasToFitTheFrameItCrops() =
    runTest {
      val sized =
        listOf(
          "second clap wider than the first" to listOf(ispe(FRAME), clap(Size(10, 10)), clap(Size(20, 20))) to null,
          "second clap inside the first" to listOf(ispe(FRAME), clap(Size(20, 20)), clap(Size(10, 10))) to
            Size(10, 10),
          "second clap fits once turned" to listOf(ispe(FRAME), clap(Size(30, 20)), irot(1), clap(Size(20, 30))) to
            Size(30, 20),
          "second clap fits only unturned" to listOf(ispe(FRAME), clap(Size(30, 20)), irot(1), clap(Size(30, 20))) to
            null,
        )
      for ((listing, size) in sized) {
        val (name, properties) = listing
        val file = heifItems(properties)

        withClue(name) { pictureExtentOf(file) shouldBe size }
        withClue("$name fetched") { ranged(file) shouldBe size }
      }
    }

  // libheif refuses an item whose first entry points past the end of the ipco. An entry it never reads may.
  @Test
  fun anIndexPastTheEndOfTheIpcoOnlyCountsInTheFirstEntryForTheItem() =
    runTest {
      val properties = listOf(ispe(FRAME), irot(1))
      val dangling = heif(properties, ipma(listOf(1 to listOf(1, 3, 2))))

      assertFailsWith<MalformedPicture> { heifExtent(ByteView(dangling, dangling.size)) }
      assertFailsWith<MalformedPicture> { heifOrientation(ByteView(dangling, dangling.size)) }
      pictureExtentOf(dangling).shouldBeNull()
      ranged(dangling).shouldBeNull()
      pictureOrientationOf(dangling) shouldBe PictureOrientation.Normal

      val passedOver = heif(properties, ipma(listOf(2 to listOf(3), 1 to listOf(1, 2), 1 to listOf(3))))
      pictureExtentOf(passedOver) shouldBe FRAME
      pictureOrientationOf(passedOver) shouldBe heifOrientationOf(1, null)
    }

  // libheif refuses an ipma above version 1 and an ispe above version 0, so neither the size nor the turn is read.
  @Test
  fun anIpmaAboveVersionOneOrAnIspeAboveVersionZeroIsRefused() =
    runTest {
      val entry = listOf(1 to listOf(1, 2))
      val ispeVersion1 = fullBox("ispe", 1, 0, bytes { u32(40).u32(30) })
      val refused =
        mapOf(
          "ipma version 2" to heif(TURNED, ipma(entry, version = 2)),
          "ispe version 1" to heif(listOf(ispeVersion1, irot(1)), ipma(entry)),
        )
      val read = heif(TURNED, ipma(entry, version = 1))

      pictureExtentOf(read) shouldBe FRAME
      pictureOrientationOf(read) shouldBe heifOrientationOf(1, null)
      for ((name, file) in refused) assertRefused(file, name)
    }

  // Every other reader of the item's properties refuses an ipma above version 1 too. Each row is the same file with
  // its ipma at version 1, which reads, and at version 2.
  @Test
  fun anIpmaAboveVersionOneLeavesNoAlphaColorCodingOrTiles() {
    val shape = { version: Int -> TestHeic(listOf(AGREEING), alphas = 1, ipmaVersion = version).bytes() }
    val grid = { version: Int -> TestHeic(listOf(AGREEING), grid = true, ipmaVersion = version).bytes() }
    val rows =
      mapOf<String, (ByteArray) -> Any?>(
        "heifAlpha" to { heifAlpha(it)?.item },
        "color reader" to { embeddedColor(it)?.descriptions?.firstOrNull() },
        "heifYcbcrCoding" to { pictureYcbcrCoding(it) },
      )
    for ((name, read) in rows) {
      withClue("$name at version 1") { read(shape(1)).shouldNotBeNull() }
      withClue("$name at version 2") { read(shape(2)).shouldBeNull() }
    }
    withClue("heifTiled at version 1") { heifPrimaryIsTiled(grid(1)).shouldBeTrue() }
    withClue("heifTiled at version 2") { heifPrimaryIsTiled(grid(2)).shouldBeFalse() }
  }

  // An entry whose associations run past the end of its ipma is refused whole, so none of them is handed over, while
  // the entry ahead of it is handed whole.
  @Test
  fun aTruncatedLastEntryHandsNoneOfItsAssociations() {
    val listed = associations(listOf(1 to listOf(1, 2), 2 to listOf(1, 2)), version = 0, wideIndex = false)
    val file = fullBox("ipma", 0, 0, listed.copyOf(listed.size - 1))
    val view = ByteView(file, file.size)
    val ipma = view.boxes(0, file.size).single()
    val handed = mutableListOf<Long>()

    assertFailsWith<MalformedPicture> { view.forEachAssociation(ipma) { item, _ -> handed += item } }
    handed shouldBe listOf(1L, 1L)
    handed.count { it == 2L } shouldBe 0
  }

  // libheif reads the first iprp alone, its first ipco and its own ipma boxes. The second iprp here lists the item
  // turned and sized, and is never read.
  @Test
  fun onlyTheFirstIprpCounts() =
    runTest {
      val entry = listOf(1 to listOf(1, 2))
      val listed = box("iprp", box("ipco", *TURNED.toTypedArray()), ipma(entry))
      val otherItem = box("iprp", box("ipco", *TURNED.toTypedArray()), ipma(listOf(2 to listOf(1, 2))))
      val firstOnly = iprps(listed, box("iprp", box("ipco", ispe(Size(20, 10))), ipma(entry)))

      pictureExtentOf(firstOnly) shouldBe FRAME
      pictureOrientationOf(firstOnly) shouldBe heifOrientationOf(1, null)
      assertRefused(iprps(otherItem, listed), "second iprp holds the only entry")
      assertRefused(iprps(box("iprp", ipma(entry)), listed), "first iprp has no ipco")
      assertRefused(iprps(box("iprp", box("ipco", *TURNED.toTypedArray())), listed), "first iprp has no ipma")
    }

  // libheif sizes both of these. It reads an ipma that counts more entries than it holds up to the end of the box, and
  // sizes an item with a clap and no ispe by the clap. Core gives no size for either and leaves the first unturned.
  @Test
  fun aShortIpmaOrAClapWithNoIspeGivesNoSize() =
    runTest {
      val oneEntryCountedAsThree = associations(listOf(1 to listOf(1, 2)), version = 0, wideIndex = false)
      oneEntryCountedAsThree[3] = 3
      val short = heif(TURNED, fullBox("ipma", 0, 0, oneEntryCountedAsThree))
      val unsized = heifItems(listOf(clap(Size(20, 30)), irot(1)))

      assertRefused(short, "ipma counting three entries and holding one")
      pictureExtentOf(unsized).shouldBeNull()
      ranged(unsized).shouldBeNull()
      pictureOrientationOf(unsized) shouldBe heifOrientationOf(1, null)
    }

  // A meta box of the largest size a walk reads, filled with entries for another item that each point 255 times at
  // the irot. The item's own entry comes last and turns it three times, and one more entry for it is never read.
  @Test
  fun aMetaFullOfLongEntriesForOtherItemsReadsOnlyTheItemsFirstEntry() =
    runTest {
      val file = longListing()

      pictureOrientationOf(file) shouldBe heifOrientationOf(3, null)
      pictureExtentOf(file) shouldBe FRAME
      ranged(file) shouldBe FRAME
    }

  private suspend fun assertRefused(
    file: ByteArray,
    name: String,
  ) {
    withClue(name) { pictureExtentOf(file).shouldBeNull() }
    withClue("$name fetched") { ranged(file).shouldBeNull() }
    withClue(name) { pictureOrientationOf(file) shouldBe PictureOrientation.Normal }
  }

  // A HEIF whose meta holds a pitm for item 1 and then each of [iprps] in order.
  private fun iprps(vararg iprps: ByteArray): ByteArray =
    ftyp("avif", "mif1") + fullBox("meta", 0, 0, fullBox("hdlr", 0, 0, ByteArray(21)), pitm(), *iprps)

  private fun pitm(): ByteArray = fullBox("pitm", 0, 0, bytes { u16(1) })

  private suspend fun ranged(file: ByteArray): Size? =
    pictureExtentOf(file.size.toLong()) { start, count -> file.copyOfRange(start.toInt(), start.toInt() + count) }

  // Built from whole arrays rather than through Bytes, which would box every byte of the 4 MiB.
  private fun longListing(): ByteArray {
    val ipco = box("ipco", ispe(FRAME), irot(1))
    val own = bytes { u16(1).u8(4).raw(byteArrayOf(1, 2, 2, 2)) }
    val ignored = entry(item = 1)
    val fileType = ftyp("avif", "mif1")
    val pitm = fullBox("pitm", 0, 0, bytes { u16(1) })
    // The meta, iprp and ipma headers, the meta's own full box fields and the ipma's flags and entry count.
    val fixed = 8 + 4 + pitm.size + 8 + ipco.size + 8 + 4 + 4 + own.size + ignored.size
    val fillers = (MAX_WALK_BLOCK - fixed) / ENTRY_SIZE
    val other = entry(item = 2)
    val entries = ByteArray(fillers * ENTRY_SIZE)
    repeat(fillers) { other.copyInto(entries, it * ENTRY_SIZE) }
    val ipmaBody = bytes { u32(fillers + 2L) } + entries + own + ignored
    val ipma = header("ipma", 4 + ipmaBody.size) + ByteArray(4) + ipmaBody
    val iprp = header("iprp", ipco.size + ipma.size) + ipco + ipma
    val meta = header("meta", 4 + pitm.size + iprp.size) + ByteArray(4) + pitm + iprp
    check(meta.size <= MAX_WALK_BLOCK) { "meta is ${meta.size} bytes" }
    return fileType + meta
  }

  private fun entry(item: Int): ByteArray =
    ByteArray(ENTRY_SIZE) { 0x82.toByte() }.also {
      it[0] = (item shr 8).toByte()
      it[1] = item.toByte()
      it[2] = 0xFF.toByte()
    }

  private fun header(
    type: String,
    body: Int,
  ): ByteArray = bytes { u32(8L + body).ascii(type) }

  private companion object {
    val FRAME = Size(40, 30)
    val TURNED = listOf(ispe(FRAME), irot(1))
    val AGREEING = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, true))).nal()

    // An item id, an association count of 255 and each association.
    const val ENTRY_SIZE = 2 + 1 + 255
  }
}
