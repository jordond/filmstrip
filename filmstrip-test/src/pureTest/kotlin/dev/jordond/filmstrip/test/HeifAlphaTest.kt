package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.ALPHA_UNREADABLE_REASON
import dev.jordond.filmstrip.picture.heif.HeifAlpha
import dev.jordond.filmstrip.picture.heif.OVERLAY_TOO_LARGE_REASON
import dev.jordond.filmstrip.picture.heif.heifAlpha
import dev.jordond.filmstrip.picture.heifPrimaryIsTiled
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Core's read of the alpha item an AVIF or HEIC carries for its primary item, on the stills [HeifStills] writes with
 * the [AlphaPlanes] beside them.
 */
class HeifAlphaTest {
  @Test
  fun `a still with no alpha item has none`() {
    heifAlpha(ColorPictures.NCLX_UNSPECIFIED.bytes()).shouldBeNull()
    heifAlpha(ColorPictures.NCLX_UNSPECIFIED.gridBytes()).shouldBeNull()
    heifAlpha(byteArrayOf(1, 2, 3)).shouldBeNull()
  }

  @Test
  fun `an AVIF and a HEIC alpha item are read by their id`() {
    for ((item, alpha) in listOf(AVIF to AlphaPlanes.avif(), HEIC to AlphaPlanes.heic())) {
      val read = withClue(item.itemType) { heifAlpha(still(item, alpha)).shouldNotBeNull() }
      read.item shouldBe 2L
      read.grid.shouldBeNull()
      read.premultiplied shouldBe false
      read.fullRange shouldBe true
      read.bitDepth shouldBe 8
    }
  }

  @Test
  fun `an iovl alpha item is laid out the way an iovl primary item is`() {
    val offsets = listOf(0 to 0, 6 to 3)
    val alphaFill = listOf(32896, 32896, 32896, 65535)
    val bytes =
      HeifOverlays.overlay(
        "avif",
        AVIF,
        offsets,
        Size(16, 12),
        alpha = AlphaPlanes.avif(),
        alphaFill = alphaFill,
      )
    val read = heifAlpha(bytes).shouldNotBeNull()
    val overlay = read.overlay.shouldNotBeNull()
    read.grid.shouldBeNull()
    read.refusal.shouldBeNull()
    overlay.inputs.map { it.x to it.y } shouldBe offsets
    overlay.size shouldBe Size(16, 12)
    listOf(overlay.fill.red, overlay.fill.green, overlay.fill.blue, overlay.fill.alpha) shouldBe alphaFill
    read.fullRange shouldBe true
    read.bitDepth shouldBe 8
  }

  @Test
  fun `an iovl alpha item past a bound carries the reason the primary read gives`() {
    val plane = AlphaPlanes.avif()
    val large = HeifAlphaPlane(plane.itemType, plane.configuration, plane.codestream, Size(16384, 16384))
    val bytes = HeifOverlays.overlay("avif", AVIF, listOf(0 to 0), Size(8, 8), alpha = large)
    val read = heifAlpha(bytes).shouldNotBeNull()
    read.refusal shouldBe OVERLAY_TOO_LARGE_REASON
    read.overlay.shouldBeNull()
  }

  @Test
  fun `an iovl alpha item reads the matrix its own codestream states`() {
    val stated = overlayAlpha(hevcAlpha(HevcVui.FullRangeBt709.configuration), Size(128, 64))
    stated.reading shouldBe ycbcrReading(BT709, BT709, SRGB_TRANSFER)
    val unstated = overlayAlpha(AlphaPlanes.avif(), Size(16, 8))
    unstated.reading shouldBe ycbcrReading(UNSPECIFIED, UNSPECIFIED, UNSPECIFIED)
    withClue("the two readings differ") { stated.reading shouldNotBe unstated.reading }
  }

  @Test
  fun `an iovl alpha item whose canvas shows and whose matrix core cannot read is refused with its reason`() {
    val plane = hevcAlpha(HevcVui.FullRangeBt709.configuration.replace(BT709_MATRIX_BITS, YDZDX_MATRIX_BITS))
    val reading = ycbcrReading(YDZDX, BT709, SRGB_TRANSFER).shouldBeInstanceOf<YcbcrReading.Refused>()
    val shown = overlayAlpha(plane, Size(128, 64))
    shown.refusal shouldBe reading.reason
    shown.overlay.shouldBeNull()
    val covered = overlayAlpha(plane, Size(64, 64))
    withClue("a canvas the input covers whole is never coded") { covered.refusal.shouldBeNull() }
  }

  @Test
  fun `a limited range AVIF and HEIC alpha item read their range off their own codestream`() {
    for ((item, alpha) in listOf(AVIF to AlphaPlanes.avif(fullRange = false), HEIC to AlphaPlanes.heic(false, false))) {
      val read = withClue(item.itemType) { heifAlpha(still(item, alpha)).shouldNotBeNull() }
      withClue(item.itemType) { read.fullRange shouldBe false }
      withClue(item.itemType) { read.bitDepth shouldBe 8 }
    }
  }

  @Test
  fun `the alpha item follows a thumbnail written ahead of the primary item`() {
    val bytes = still(AVIF, AlphaPlanes.avif(), thumbnail = ColorPictures.NCLX_BT709_LIMITED.thumbnail())
    heifAlpha(bytes).shouldNotBeNull().item shouldBe 3L
  }

  @Test
  fun `a premultiplied alpha item says so`() {
    heifAlpha(still(AVIF, AlphaPlanes.avif(premultiplied = true))).shouldNotBeNull().premultiplied shouldBe true
  }

  @Test
  fun `an alpha grid is read as its tiles in rows and the size it crops to`() {
    val bytes =
      HeifStills.grid(
        "avif",
        AVIF.itemType,
        AVIF.configuration,
        AVIF.codestream,
        AVIF.size,
        columns = 3,
        rows = 2,
        size = Size(20, 12),
        alpha = List(6) { AlphaPlanes.avifTile(it) },
      )
    heifPrimaryIsTiled(bytes) shouldBe true
    val alpha = heifAlpha(bytes).shouldNotBeNull()
    alpha.item shouldBe 8L
    val grid = alpha.grid.shouldNotBeNull()
    grid.tiles shouldBe (9L..14L).toList()
    grid.columns shouldBe 3
    grid.rows shouldBe 2
    grid.size shouldBe Size(20, 12)
    alpha.fullRange shouldBe true
  }

  @Test
  fun `an iovl alpha item whose layout does not read is refused rather than left out`() {
    val fill = listOf(0x1234, 0x5678, 0x9ABC, 0xDEF0)
    val bytes =
      HeifOverlays.overlay("avif", AVIF, listOf(0 to 0), Size(8, 8), alpha = AlphaPlanes.avif(), alphaFill = fill)
    val version = bytes.indexOf(fill.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }.toByteArray()) - 2
    heifAlpha(bytes).shouldNotBeNull().refusal.shouldBeNull()

    bytes[version] = 1
    val read = heifAlpha(bytes).shouldNotBeNull()
    read.refusal shouldBe ALPHA_UNREADABLE_REASON
    read.overlay.shouldBeNull()
  }

  @Test
  fun `a grid alpha item whose layout does not read is refused rather than left out`() {
    val bytes =
      HeifStills.grid(
        "avif",
        AVIF.itemType,
        AVIF.configuration,
        AVIF.codestream,
        AVIF.size,
        columns = 3,
        rows = 2,
        alpha = List(6) { AlphaPlanes.avifTile(it) },
      )
    val layout = byteArrayOf(0, 0, 1, 2, 0, 24, 0, 16)
    val rows =
      (0..bytes.size - layout.size).last { start -> layout.indices.all { bytes[start + it] == layout[it] } } + 2
    heifAlpha(bytes).shouldNotBeNull().refusal.shouldBeNull()

    bytes[rows] = 2
    val read = heifAlpha(bytes).shouldNotBeNull()
    read.refusal shouldBe ALPHA_UNREADABLE_REASON
    read.grid.shouldBeNull()
  }

  @Test
  fun `an auxiliary item of another type is not an alpha item`() {
    val depth = AlphaPlanes.avif()
    val bytes = still(AVIF, depth)
    val alphaType = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".encodeToByteArray()
    val depthType = "urn:mpeg:mpegB:cicp:systems:auxiliary:depth".encodeToByteArray()
    val at = bytes.indexOf(alphaType)
    alphaType.indices.forEach { bytes[at + it] = depthType[it] }
    heifAlpha(bytes).shouldBeNull()
  }

  // The alpha item's entry comes last in the ipma, and claiming one more association than it holds runs it past the
  // end of the box, so the still has no alpha.
  @Test
  fun `an alpha item whose ipma entry runs past the box has none`() {
    val bytes = still(AVIF, AlphaPlanes.avif())
    val ipma = bytes.indexOf("ipma".encodeToByteArray())
    val ipmaEnd = ipma - 4 + (0 until 4).fold(0) { size, at -> (size shl 8) or (bytes[ipma - 4 + at].toInt() and 0xFF) }
    val primaryCount = ipma + 4 + 4 + 4 + 2
    val alphaCount = primaryCount + 1 + bytes[primaryCount] + 2
    bytes[alphaCount].toInt() shouldBe 4
    alphaCount + 1 + 4 shouldBe ipmaEnd
    heifAlpha(bytes).shouldNotBeNull()

    bytes[alphaCount] = 5
    heifAlpha(bytes).shouldBeNull()
  }

  private fun still(
    item: HeifThumbnail,
    alpha: HeifAlphaPlane,
    thumbnail: HeifThumbnail? = null,
  ): ByteArray {
    val brand = if (item.itemType == "av01") "avif" else "heic"
    return HeifStills.still(
      brand,
      item.itemType,
      item.configuration,
      item.codestream,
      item.size,
      thumbnail = thumbnail,
      alpha = alpha,
    )
  }

  // The alpha item core reads out of an AVIF overlay of one input on [canvas], whose alpha is [plane] at the top left.
  private fun overlayAlpha(
    plane: HeifAlphaPlane,
    canvas: Size,
  ): HeifAlpha = heifAlpha(HeifOverlays.overlay("avif", AVIF, listOf(0 to 0), canvas, alpha = plane)).shouldNotBeNull()

  // A sixty-four pixel HEVC alpha plane whose hvcC holds [configuration]. Core reads its signal off the hvcC alone, so
  // its coded data is a stand in.
  private fun hevcAlpha(configuration: String): HeifAlphaPlane =
    HeifAlphaPlane("hvc1", HeifStills.box("hvcC", configuration.hex()), ByteArray(4), Size(64, 64))

  private fun ByteArray.indexOf(part: ByteArray): Int =
    (0..size - part.size).first { start -> part.indices.all { this[start + it] == part[it] } }

  private companion object {
    val AVIF = ColorPictures.NCLX_UNSPECIFIED.thumbnail()
    val HEIC = ColorPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    const val BT709 = 1
    const val SRGB_TRANSFER = 13
    const val UNSPECIFIED = 2
    const val YDZDX = 11

    // The SPS bytes from the full range flag through the matrix of HevcVui.FullRangeBt709, and the same with the matrix
    // coefficients written as 11 for YDzDx. The matrix's eight bits straddle the fourth and fifth bytes.
    const val BT709_MATRIX_BITS = "b9b80868082"
    const val YDZDX_MATRIX_BITS = "b9b80868582"
  }
}
