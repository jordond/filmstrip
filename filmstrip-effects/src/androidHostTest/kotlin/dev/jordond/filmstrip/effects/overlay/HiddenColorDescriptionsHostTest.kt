package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.picture.withColorDescriptionsHidden
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeicColorPictures
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.IccPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Every color description a picture carries hidden from Android's decoder, read back byte by byte.
 *
 * Each container is built here with a description [pictureColor] reads, so the check that it is hidden is that the
 * same reader no longer finds it, with every byte outside the rewritten fields where it was.
 * [DamagedColorDescriptionsHostTest] hands it damaged and truncated files.
 */
class HiddenColorDescriptionsHostTest {
  @Test
  fun aPngLosesEveryColorChunk() {
    val tagged =
      png(chunk("iCCP", ICCP_PAYLOAD), chunk("gAMA", GAMMA), chunk("cHRM", CHROMA), chunk("cICP", P3_CICP))
    withClue("the test PNG carries no color the reader finds") { pictureColor(tagged).shouldNotBeNull() }

    val hidden = tagged.withColorDescriptionsHidden()

    withClue("the hidden PNG still carries a color") { pictureColor(hidden).shouldBeNull() }
    hidden.chunkTypes().filter { it[1].isLowerCase() } shouldBe listOf("icCP", "gaMA", "chRM", "ciCP")
    assertEveryCrcHolds(hidden)
  }

  @Test
  fun aPngWithoutAProfileLosesItsGammaAndChromaticities() {
    val untagged = png(chunk("gAMA", GAMMA), chunk("cHRM", CHROMA))

    val hidden = untagged.withColorDescriptionsHidden()

    hidden.chunkTypes() shouldBe listOf("IHDR", "gaMA", "chRM", "IDAT", "IEND")
    assertEveryCrcHolds(hidden)
  }

  @Test
  fun aPngWithOnlyCicpLosesIt() {
    val hidden = png(chunk("cICP", P3_CICP)).withColorDescriptionsHidden()

    hidden.chunkTypes() shouldBe listOf("IHDR", "ciCP", "IDAT", "IEND")
    assertEveryCrcHolds(hidden)
  }

  @Test
  fun aPngMarkedSrgbComesBackAsTheSameArray() {
    val marked = png(chunk("sRGB", byteArrayOf(0)))

    marked.withColorDescriptionsHidden() shouldBeSameInstanceAs marked
  }

  // Only the markers ahead of the scan are walked, so a start and end of scan stand in for the image.
  @Test
  fun anRgbJpegLosesOnlyItsProfileSignature() {
    val tagged = jpeg(jpegProfile(PROFILE))
    withClue("the test JPEG carries no color the reader finds") { pictureColor(tagged).shouldNotBeNull() }

    val hidden = tagged.withColorDescriptionsHidden()

    withClue("the hidden JPEG still carries a color") { pictureColor(hidden).shouldBeNull() }
    tagged.indices.filter { tagged[it] != hidden[it] } shouldBe listOf(SEGMENT_SIGNATURE_AT)
  }

  @Test
  fun aGreyJpegLosesItsProfileAcrossEverySegment() {
    val grey = IccPictures.GREY.profile
    val half = grey.size / 2
    val tagged = jpeg(jpegProfile(grey.copyOf(half), 1, 2) + jpegProfile(grey.copyOfRange(half, grey.size), 2, 2))

    val hidden = tagged.withColorDescriptionsHidden()

    withClue("the hidden JPEG still carries a color") { pictureColor(hidden).shouldBeNull() }
    withClue("a segment kept its signature") { tagged.indices.count { tagged[it] != hidden[it] } shouldBe 2 }
  }

  @Test
  fun aCmykOrYcckJpegKeepsTheProfileItDecodesThrough() {
    for (space in listOf("CMYK", "YCCK")) {
      val tagged = jpeg(jpegProfile(profileOf(space)))

      withClue("a $space JPEG lost its profile") { tagged.withColorDescriptionsHidden() shouldBeSameInstanceAs tagged }
    }
  }

  @Test
  fun aWebpLosesItsProfileFlagAndChunk() {
    val tagged = webp(riffChunk("VP8X", VP8X_FLAGGED) + riffChunk("ICCP", PROFILE))
    withClue("the test WebP carries no color the reader finds") { pictureColor(tagged).shouldNotBeNull() }

    val hidden = tagged.withColorDescriptionsHidden()

    withClue("the hidden WebP still carries a color") { pictureColor(hidden).shouldBeNull() }
    withClue("the VP8X header still flags a profile") { hidden[VP8X_FLAGS_AT].toInt() shouldBe 0 }
    String(hidden, ICCP_CHUNK_AT, 4, Charsets.ISO_8859_1) shouldBe "XCCP"
  }

  @Test
  fun aWebpWalksPastAnOddChunksPadding() {
    val odd = riffChunk("XMP ", ByteArray(3) { 1 })
    val tagged = webp(riffChunk("VP8X", VP8X_FLAGGED) + odd + riffChunk("ICCP", PROFILE))

    withClue("the profile past the padding was missed") {
      pictureColor(tagged.withColorDescriptionsHidden()).shouldBeNull()
    }
  }

  @Test
  fun anHeifLosesItsProfileAndItsPrimariesAndTransfer() {
    val tagged = listOf(ColorPictures.NCLX_DISPLAY_P3, ColorPictures.NCLX_DISPLAY_P3_WITH_PROFILE)
    for (picture in tagged.map { it.bytes() } + ColorPictures.HEIC_PROFILE_DISPLAY_P3.bytes()) {
      withClue("the test HEIF carries no color the reader finds") { pictureColor(picture).shouldNotBeNull() }

      withClue("the hidden HEIF still carries a color") {
        pictureColor(picture.withColorDescriptionsHidden()).shouldBeNull()
      }
    }
  }

  @Test
  fun anHeifKeepsTheMatrixAndRangeItsDecoderReads() {
    for (picture in listOf(ColorPictures.NCLX_DISPLAY_P3.bytes(), ColorPictures.HEIC_NCLX_BT709_LIMITED.bytes())) {
      val hidden = picture.withColorDescriptionsHidden()

      val unspecified = pictureYcbcrCoding(picture)?.copy(primaries = 2, transfer = 2)
      withClue("the hidden HEIF lost its matrix or range") { pictureYcbcrCoding(hidden) shouldBe unspecified }
      val nclx = hidden.indexOf("nclx") + 4
      hidden.copyOfRange(nclx, nclx + UNSPECIFIED.size) shouldBe UNSPECIFIED
    }
  }

  @Test
  fun anHeifKeepingItsPrimariesLosesOnlyItsTransfer() {
    val picture = ColorPictures.NCLX_DISPLAY_P3.bytes()

    val hidden = picture.withColorDescriptionsHidden(keepNclxPrimaries = true)

    val nclx = hidden.indexOf("nclx") + 4
    withClue("its primaries") { hidden.copyOfRange(nclx, nclx + 2) shouldBe picture.copyOfRange(nclx, nclx + 2) }
    withClue("its transfer") { hidden.copyOfRange(nclx + 2, nclx + 4) shouldBe UNSPECIFIED.copyOfRange(2, 4) }
  }

  @Test
  fun anHeifChangesOnlyItsColorFields() {
    val tagged = ColorPictures.NCLX_DISPLAY_P3_WITH_PROFILE.bytes()

    val hidden = tagged.withColorDescriptionsHidden()

    withClue("more than the color fields changed") { tagged.indices.count { tagged[it] != hidden[it] } shouldBe 4 + 2 }
  }

  // A reader must understand a property marked essential, so a profile renamed out of every reader's sight loses the
  // mark in each width an ipma box writes, and only that property does.
  @Test
  fun anHeifProfileBecomesABoxNoReaderKnowsAndLosesItsEssentialMark() {
    val nclx = "nclx".encodeToByteArray() + be16(1) + be16(13) + be16(1) + byteArrayOf(FULL_RANGE)
    val hiddenNclx = "nclx".encodeToByteArray() + be16(2) + be16(2) + be16(1) + byteArrayOf(FULL_RANGE)
    val profile = "prof".encodeToByteArray() + PROFILE
    val layouts =
      listOf(
        Triple(0, false, intArrayOf(0x81, 0x82)) to intArrayOf(0x01, 0x82),
        Triple(1, false, intArrayOf(0x81, 0x82)) to intArrayOf(0x01, 0x82),
        Triple(0, true, intArrayOf(0x8001, 0x8002)) to intArrayOf(0x0001, 0x8002),
      )
    for ((layout, cleared) in layouts) {
      val (version, wide, marked) = layout
      val tagged = heif(box("colr", profile) + box("colr", nclx), ipma(version, wide, *marked))

      val hidden = tagged.withColorDescriptionsHidden()

      val expected = heif(box("xxxx", profile) + box("colr", hiddenNclx), ipma(version, wide, *cleared))
      withClue("ipma version $version, wide $wide") { hidden shouldBe expected }
    }
  }

  // Item 1 is a thumbnail whose own nclx sits where its ispe was, item 2 the primary item. Core restates only the nclx
  // of the primary item and its tiles.
  @Test
  fun aRestateStatesTheMatrixOnlyInThePrimaryItemsNclx() {
    val item = HeicColorPictures.TWELVE_BIT_ITEM
    val nclx = CicpTag(BT709, SRGB_TRANSFER, SMPTE_240, true)
    val tagged =
      HeifStills.still("heic", item.itemType, item.configuration, item.codestream, SIZE, nclx, thumbnail = item)
    val fields = be16(BT709) + be16(SRGB_TRANSFER) + be16(ICTCP) + ByteArray(2)
    val ownNclx = box("colr", "nclx".encodeToByteArray() + fields)
    val file = tagged.copyOf().also { ownNclx.copyInto(it, tagged.lastIndexOf("ispe") - 4) }
    for (sdk in listOf(30, 34, 36)) {
      val coding = pictureYcbcrCoding(file).shouldNotBeNull()
      val readable = readableMatrices(true, sdk, avif = false, tenBit = coding.tenBitSamples)
      val nearest =
        coding.reading
          .nearestReadable(readable)
          .shouldNotBeNull()
          .matrix

      val prepared = file.forAndroidDecoder(sdk).bytes

      val primaryAt = prepared.indexOf("nclx") + 4
      val thumbnailAt = prepared.lastIndexOf("nclx") + 4
      withClue("API $sdk") { pictureYcbcrCoding(prepared)?.codestream?.matrix shouldBe nearest }
      withClue("API $sdk") { prepared.copyOfRange(primaryAt + 4, primaryAt + 6) shouldBe be16(nearest) }
      prepared.copyOfRange(thumbnailAt + 4, thumbnailAt + 7) shouldBe be16(ICTCP) + byteArrayOf(0)
      prepared.copyOfRange(thumbnailAt, thumbnailAt + UNSPECIFIED.size) shouldBe UNSPECIFIED
    }
  }

  @Test
  fun anHeifNclxTooShortForItsFieldsKeepsEveryByteItCannotHold() {
    val transferOnly = "nclx".encodeToByteArray() + be16(1) + be16(13)
    val typeOnly = "nclx".encodeToByteArray() + be16(1)
    val tagged = heif(box("colr", transferOnly))

    val hidden = tagged.withColorDescriptionsHidden()

    hidden shouldBe heif(box("colr", "nclx".encodeToByteArray() + UNSPECIFIED))
    val short = heif(box("colr", typeOnly))
    short.withColorDescriptionsHidden() shouldBeSameInstanceAs short
  }

  @Test
  fun anHeifBoxSizedZeroRunsToTheEndOfItsParent() {
    val colr = box("colr", "prof".encodeToByteArray() + PROFILE)
    val open = ByteArray(4) + "colr".encodeToByteArray() + colr.copyOfRange(8, colr.size)

    withClue("the open ended box was skipped") { pictureColor(heif(open).withColorDescriptionsHidden()).shouldBeNull() }
  }

  private companion object {
    val GAMMA = be32(45455)
    val CHROMA = ByteArray(32) { it.toByte() }
    val UNSPECIFIED = be16(2) + be16(2)
    const val FULL_RANGE = 0x80.toByte()
    const val BT709 = 1
    const val SMPTE_240 = 7
    const val SRGB_TRANSFER = 13
    const val ICTCP = 14
    val SIZE = Size(64, 64)
    const val VP8X_FLAGS_AT = 20
    const val ICCP_CHUNK_AT = 30
  }
}
