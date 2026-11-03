package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.fixtures.BoxSize
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.sizedBox
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Splices bytes into a hand-built HEIF of an `ftyp`, a `meta` holding an `iloc` and a `free` box, and an `mdat` whose
 * payload is the one item's data, and checks every box size and item location the splices move.
 */
class HeifSpliceTest {
  @Test
  fun `a splice ahead of the item data grows its boxes and moves the item's offset`() {
    val file = TestFile()
    val splice = Splice(file.freePayload + 2, file.freePayload + 4, ByteArray(6) { 0x55 })

    val fields = file.fields(listOf(splice))
    val out = spliced(file.bytes, fields, listOf(splice)).shouldNotBeNull()

    out.size shouldBe file.bytes.size + 4
    val rewritten = Rewritten(out, listOf(splice))
    withClue("the boxes no longer walk to where they should end") { rewritten.keepsBoxes(file.top).shouldBeTrue() }
    withClue("the item lost its data") { rewritten.keepsItemData(file.view, file.locations, null).shouldBeTrue() }
    out.copyOfRange(file.payload + 4, file.payload + 4 + PAYLOAD.size) shouldBe PAYLOAD
  }

  @Test
  fun `a splice inside the item data resizes its extent and leaves its offset alone`() {
    val file = TestFile()
    val splice = Splice(file.payload + 2, file.payload + 4, ByteArray(5) { 0x66 })

    val extent =
      file.locations.items
        .single()
        .extents
        .single()
    val fields = file.locations.fields(listOf(splice), null).shouldNotBeNull()
    val out = spliced(file.bytes, file.sizes(listOf(splice)) + fields, listOf(splice)).shouldNotBeNull()

    fields.map { it.at to it.value } shouldBe listOf(extent.lengthAt to PAYLOAD.size + 3L)
    Rewritten(out, listOf(splice)).keepsItemData(file.view, file.locations, null).shouldBeTrue()
  }

  @Test
  fun `the boxes holding a splice grow by it and a box sized to the end takes no field`() {
    val file = TestFile(mdatSize = BoxSize.ToEnd)
    val splice = Splice(file.payload + 1, file.payload + 1, ByteArray(3))

    val sizes = file.sizes(listOf(splice))

    sizes.shouldBeEmpty()
    val out = spliced(file.bytes, sizes + file.fields(listOf(splice)), listOf(splice)).shouldNotBeNull()
    Rewritten(out, listOf(splice)).keepsBoxes(file.top).shouldBeTrue()
  }

  @Test
  fun `a box with a 64 bit size has that size moved rather than its 32 bit field`() {
    val file = TestFile(mdatSize = BoxSize.Large)
    val splice = Splice(file.payload + 1, file.payload + 1, ByteArray(3))

    val size = file.sizes(listOf(splice)).single()

    size.at shouldBe file.top.last().at + 8
    size.width shouldBe 8
    size.value shouldBe 16L + PAYLOAD.size + 3
    val out = spliced(file.bytes, listOf(size) + file.fields(listOf(splice)), listOf(splice)).shouldNotBeNull()
    Rewritten(out, listOf(splice)).keepsBoxes(file.top).shouldBeTrue()
  }

  @Test
  fun `item data left where the splices moved it no longer holds the item`() {
    val file = TestFile()
    val splice = Splice(file.freePayload + 2, file.freePayload + 4, ByteArray(6))

    val out = spliced(file.bytes, file.sizes(listOf(splice)), listOf(splice)).shouldNotBeNull()

    Rewritten(out, listOf(splice)).keepsBoxes(file.top).shouldBeTrue()
    Rewritten(out, listOf(splice)).keepsItemData(file.view, file.locations, null).shouldBeFalse()
    withClue("no items lost nothing") {
      Rewritten(out, listOf(splice)).keepsItemData(file.view, null, null).shouldBeTrue()
    }
  }

  @Test
  fun `a splice across either end of an extent cannot be moved`() {
    val file = TestFile()

    file.locations.fields(listOf(Splice(file.payload - 1, file.payload + 1, ByteArray(0))), null).shouldBeNull()
    file.locations
      .fields(listOf(Splice(file.payload + PAYLOAD.size - 1, file.payload + PAYLOAD.size + 1, ByteArray(0))), null)
      .shouldBeNull()
  }

  @Test
  fun `an item in idat needs the idat and an external item is left alone`() {
    val inIdat = ItemLocations(4, 4, 0, listOf(ItemLocation(1, 1, false, 0, 0, listOf(Extent(0, 0, 4, 4)))))
    val external = ItemLocations(4, 4, 0, listOf(ItemLocation(1, 0, true, 0, 0, listOf(Extent(40, 0, 4, 4)))))
    val splice = Splice(10, 10, ByteArray(8))

    inIdat.fields(listOf(splice), idat = null).shouldBeNull()
    external.fields(listOf(splice), null).shouldNotBeNull().shouldBeEmpty()
  }

  @Test
  fun `a field that overlaps a splice or does not fit its width is refused`() {
    val bytes = ByteArray(32)
    val splice = Splice(8, 12, ByteArray(2))

    spliced(bytes, listOf(Field(10, 4, 1)), listOf(splice)).shouldBeNull()
    spliced(bytes, listOf(Field(0, 4, 0x1_0000_0000L)), listOf(splice)).shouldBeNull()
    spliced(bytes, listOf(Field(0, 4, -1)), listOf(splice)).shouldBeNull()
    val wide = spliced(bytes, listOf(Field(16, 8, 0x1_0000_0002L)), listOf(splice)).shouldNotBeNull()
    wide.copyOfRange(14, 22) shouldBe byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2)
  }

  @Test
  fun `a position moves by every splice that ends at or before it`() {
    val splices = listOf(Splice(4, 6, ByteArray(5)), Splice(10, 14, ByteArray(1)))
    val rewritten = Rewritten(ByteArray(0), splices)

    rewritten.moved(3) shouldBe 3
    rewritten.moved(5) shouldBe 5
    rewritten.moved(6) shouldBe 9
    rewritten.moved(12) shouldBe 15
    rewritten.moved(14) shouldBe 14
  }

  @Test
  fun `the leading boxes stop at the first that does not parse and at the limit`() {
    val file = TestFile()
    val broken = file.bytes + bytes { u32(4).ascii("junk") }
    val view = ByteView(broken, broken.size)

    view.leadingBoxes().map { it.type } shouldBe file.top.map { it.type }
    view.leadingBoxes(limit = 2).map { it.type } shouldBe file.top.take(2).map { it.type }
  }

  /**
   * The file every test splices into, with where its parts sit read back out of it.
   */
  private class TestFile(
    mdatSize: BoxSize = BoxSize.Exact,
  ) {
    val bytes: ByteArray
    val view: ByteView
    val top: List<Box>
    val locations: ItemLocations
    val freePayload: Int
    val payload: Int

    init {
      val ftyp = box("ftyp", bytes { ascii("heic").u32(0) })
      val free = box("free", ByteArray(8))

      fun meta(offset: Long) = fullBox("meta", 0, 0, iloc(offset), free)
      val mdatHeader = if (mdatSize == BoxSize.Large) 16 else 8
      val placed = ftyp.size + meta(0).size + mdatHeader
      bytes = ftyp + meta(placed.toLong()) + sizedBox("mdat", mdatSize, PAYLOAD)
      view = ByteView(bytes, bytes.size)
      top = view.leadingBoxes()
      val parts = view.metaParts().shouldNotBeNull()
      locations = parts.locations.shouldNotBeNull()
      freePayload = view.boxes(parts.meta.start + 4, parts.meta.end).single { it.type == FREE }.start
      payload = placed
    }

    fun sizes(splices: List<Splice>): List<Field> = mutableListOf<Field>().also { view.sizeFields(top, splices, it) }

    fun fields(splices: List<Splice>): List<Field> = sizes(splices) + locations.fields(splices, null).shouldNotBeNull()

    // A version 0 iloc with 4-byte offsets and lengths and no base offset, listing one item of one extent.
    private fun iloc(offset: Long): ByteArray =
      fullBox(
        "iloc",
        0,
        0,
        bytes {
          u8(0x44).u8(0x00)
          u16(1)
          u16(1).u16(0)
          u16(1)
          u32(offset).u32(PAYLOAD.size.toLong())
        },
      )
  }

  private companion object {
    val PAYLOAD = ByteArray(10) { (it + 1).toByte() }
    val FREE = fourcc("free")
  }
}
