package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicColourPictures
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.IccPictures
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Every colour description a picture carries hidden from Android's decoder, read back byte by byte.
 *
 * Each container is built here with a description [pictureColour] reads, so the check that it is hidden is that the
 * same reader no longer finds it, with every byte outside the rewritten fields where it was.
 * [DamagedColourDescriptionsHostTest] hands it damaged and truncated files.
 */
class HiddenColourDescriptionsHostTest {
  @Test
  fun aPngLosesEveryColourChunk() {
    val tagged =
      png(chunk("iCCP", ICCP_PAYLOAD), chunk("gAMA", GAMMA), chunk("cHRM", CHROMA), chunk("cICP", P3_CICP))
    assertNotNull(pictureColour(tagged), "the test PNG carries no colour the reader finds")

    val hidden = tagged.withColourDescriptionsHidden()

    assertNull(pictureColour(hidden), "the hidden PNG still carries a colour")
    assertEquals(listOf("icCP", "gaMA", "chRM", "ciCP"), hidden.chunkTypes().filter { it[1].isLowerCase() })
    assertEveryCrcHolds(hidden)
  }

  @Test
  fun aPngWithoutAProfileLosesItsGammaAndChromaticities() {
    val untagged = png(chunk("gAMA", GAMMA), chunk("cHRM", CHROMA))

    val hidden = untagged.withColourDescriptionsHidden()

    assertEquals(listOf("IHDR", "gaMA", "chRM", "IDAT", "IEND"), hidden.chunkTypes())
    assertEveryCrcHolds(hidden)
  }

  @Test
  fun aPngWithOnlyCicpLosesIt() {
    val hidden = png(chunk("cICP", P3_CICP)).withColourDescriptionsHidden()

    assertEquals(listOf("IHDR", "ciCP", "IDAT", "IEND"), hidden.chunkTypes())
    assertEveryCrcHolds(hidden)
  }

  @Test
  fun aPngMarkedSrgbComesBackAsTheSameArray() {
    val marked = png(chunk("sRGB", byteArrayOf(0)))

    assertSame(marked, marked.withColourDescriptionsHidden())
  }

  // Only the markers ahead of the scan are walked, so a start and end of scan stand in for the image.
  @Test
  fun anRgbJpegLosesOnlyItsProfileSignature() {
    val tagged = jpeg(jpegProfile(PROFILE))
    assertNotNull(pictureColour(tagged), "the test JPEG carries no colour the reader finds")

    val hidden = tagged.withColourDescriptionsHidden()

    assertNull(pictureColour(hidden), "the hidden JPEG still carries a colour")
    assertEquals(listOf(SEGMENT_SIGNATURE_AT), tagged.indices.filter { tagged[it] != hidden[it] })
  }

  @Test
  fun aGreyJpegLosesItsProfileAcrossEverySegment() {
    val grey = IccPictures.GREY.profile
    val half = grey.size / 2
    val tagged = jpeg(jpegProfile(grey.copyOf(half), 1, 2) + jpegProfile(grey.copyOfRange(half, grey.size), 2, 2))

    val hidden = tagged.withColourDescriptionsHidden()

    assertNull(pictureColour(hidden), "the hidden JPEG still carries a colour")
    assertEquals(2, tagged.indices.count { tagged[it] != hidden[it] }, "a segment kept its signature")
  }

  @Test
  fun aCmykOrYcckJpegKeepsTheProfileItDecodesThrough() {
    for (space in listOf("CMYK", "YCCK")) {
      val tagged = jpeg(jpegProfile(profileOf(space)))

      assertSame(tagged, tagged.withColourDescriptionsHidden(), "a $space JPEG lost its profile")
    }
  }

  @Test
  fun aWebpLosesItsProfileFlagAndChunk() {
    val tagged = webp(riffChunk("VP8X", VP8X_FLAGGED) + riffChunk("ICCP", PROFILE))
    assertNotNull(pictureColour(tagged), "the test WebP carries no colour the reader finds")

    val hidden = tagged.withColourDescriptionsHidden()

    assertNull(pictureColour(hidden), "the hidden WebP still carries a colour")
    assertEquals(0, hidden[VP8X_FLAGS_AT].toInt(), "the VP8X header still flags a profile")
    assertEquals("XCCP", String(hidden, ICCP_CHUNK_AT, 4, Charsets.ISO_8859_1))
  }

  @Test
  fun aWebpWalksPastAnOddChunksPadding() {
    val odd = riffChunk("XMP ", ByteArray(3) { 1 })
    val tagged = webp(riffChunk("VP8X", VP8X_FLAGGED) + odd + riffChunk("ICCP", PROFILE))

    assertNull(pictureColour(tagged.withColourDescriptionsHidden()), "the profile past the padding was missed")
  }

  @Test
  fun anHeifLosesItsProfileAndItsPrimariesAndTransfer() {
    val tagged = listOf(ColourPictures.NCLX_DISPLAY_P3, ColourPictures.NCLX_DISPLAY_P3_WITH_PROFILE)
    for (picture in tagged.map { it.bytes() } + ColourPictures.HEIC_PROFILE_DISPLAY_P3.bytes()) {
      assertNotNull(pictureColour(picture), "the test HEIF carries no colour the reader finds")

      assertNull(pictureColour(picture.withColourDescriptionsHidden()), "the hidden HEIF still carries a colour")
    }
  }

  @Test
  fun anHeifKeepsTheMatrixAndRangeItsDecoderReads() {
    for (picture in listOf(ColourPictures.NCLX_DISPLAY_P3.bytes(), ColourPictures.HEIC_NCLX_BT709_LIMITED.bytes())) {
      val hidden = picture.withColourDescriptionsHidden()

      val unspecified = pictureYcbcrCoding(picture)?.copy(primaries = 2, transfer = 2)
      assertEquals(unspecified, pictureYcbcrCoding(hidden), "the hidden HEIF lost its matrix or range")
      val nclx = hidden.indexOf("nclx") + 4
      assertContentEquals(UNSPECIFIED, hidden.copyOfRange(nclx, nclx + UNSPECIFIED.size))
    }
  }

  @Test
  fun anHeifKeepingItsPrimariesLosesOnlyItsTransfer() {
    val picture = ColourPictures.NCLX_DISPLAY_P3.bytes()

    val hidden = picture.withColourDescriptionsHidden(keepNclxPrimaries = true)

    val nclx = hidden.indexOf("nclx") + 4
    assertContentEquals(picture.copyOfRange(nclx, nclx + 2), hidden.copyOfRange(nclx, nclx + 2), "its primaries")
    assertContentEquals(UNSPECIFIED.copyOfRange(2, 4), hidden.copyOfRange(nclx + 2, nclx + 4), "its transfer")
  }

  @Test
  fun anHeifChangesOnlyItsColourFields() {
    val tagged = ColourPictures.NCLX_DISPLAY_P3_WITH_PROFILE.bytes()

    val hidden = tagged.withColourDescriptionsHidden()

    assertEquals(4 + 2, tagged.indices.count { tagged[it] != hidden[it] }, "more than the colour fields changed")
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

      val hidden = tagged.withColourDescriptionsHidden()

      val expected = heif(box("xxxx", profile) + box("colr", hiddenNclx), ipma(version, wide, *cleared))
      assertContentEquals(expected, hidden, "ipma version $version, wide $wide")
    }
  }

  // Item 1 is a thumbnail whose own nclx sits where its ispe was, item 2 the primary item. Core restates only the nclx
  // of the primary item and its tiles.
  @Test
  fun aRestateStatesTheMatrixOnlyInThePrimaryItemsNclx() {
    val item = HeicColourPictures.TWELVE_BIT_ITEM
    val nclx = CicpTag(BT709, SRGB_TRANSFER, SMPTE_240, true)
    val tagged =
      HeifStills.still("heic", item.itemType, item.configuration, item.codestream, SIZE, nclx, thumbnail = item)
    val fields = be16(BT709) + be16(SRGB_TRANSFER) + be16(ICTCP) + ByteArray(2)
    val ownNclx = box("colr", "nclx".encodeToByteArray() + fields)
    val file = tagged.copyOf().also { ownNclx.copyInto(it, tagged.lastIndexOf("ispe") - 4) }
    for (sdk in listOf(30, 34, 36)) {
      val coding = assertNotNull(pictureYcbcrCoding(file))
      val readable = readableMatrices(true, sdk, avif = false, tenBit = coding.tenBitSamples)
      val nearest = assertNotNull(coding.reading.nearestReadable(readable)).matrix

      val prepared = file.forAndroidDecoder(sdk).bytes

      val primaryAt = prepared.indexOf("nclx") + 4
      val thumbnailAt = prepared.lastIndexOf("nclx") + 4
      assertEquals(nearest, pictureYcbcrCoding(prepared)?.codestream?.matrix, "API $sdk")
      assertContentEquals(be16(nearest), prepared.copyOfRange(primaryAt + 4, primaryAt + 6), "API $sdk")
      assertContentEquals(be16(ICTCP) + byteArrayOf(0), prepared.copyOfRange(thumbnailAt + 4, thumbnailAt + 7))
      assertContentEquals(UNSPECIFIED, prepared.copyOfRange(thumbnailAt, thumbnailAt + UNSPECIFIED.size))
    }
  }

  @Test
  fun anHeifNclxTooShortForItsFieldsKeepsEveryByteItCannotHold() {
    val transferOnly = "nclx".encodeToByteArray() + be16(1) + be16(13)
    val typeOnly = "nclx".encodeToByteArray() + be16(1)
    val tagged = heif(box("colr", transferOnly))

    val hidden = tagged.withColourDescriptionsHidden()

    assertContentEquals(heif(box("colr", "nclx".encodeToByteArray() + UNSPECIFIED)), hidden)
    val short = heif(box("colr", typeOnly))
    assertSame(short, short.withColourDescriptionsHidden())
  }

  @Test
  fun anHeifBoxSizedZeroRunsToTheEndOfItsParent() {
    val colr = box("colr", "prof".encodeToByteArray() + PROFILE)
    val open = ByteArray(4) + "colr".encodeToByteArray() + colr.copyOfRange(8, colr.size)

    assertNull(pictureColour(heif(open).withColourDescriptionsHidden()), "the open ended box was skipped")
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
