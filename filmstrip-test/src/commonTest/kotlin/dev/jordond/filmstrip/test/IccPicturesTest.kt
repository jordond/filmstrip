package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.pictureLight
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shape of the tagged pictures' profiles and the PNGs that carry them.
 *
 * Each profile's header and tag table are read field by field, and each PNG is walked chunk by chunk to the
 * profile it embeds and the texel it is painted with. Each PNG also goes through the same profile reader a backend
 * uses, and its texel must land on the same light as it does through the profile itself.
 */
class IccPicturesTest {
  @Test
  fun everyProfileDeclaresItsOwnSizeAndTheIccSignature() {
    for (picture in IccPictures.ALL) {
      val profile = picture.profile

      assertEquals(profile.size, profile.int32At(0), "$picture size")
      assertEquals("acsp", profile.textAt(SIGNATURE_OFFSET), "$picture signature")
      assertEquals(VERSION_4_3, profile.int32At(VERSION_OFFSET), "$picture version")
      assertEquals("XYZ ", profile.textAt(PCS_OFFSET), "$picture connection space")
    }
  }

  @Test
  fun greyIsTheOnlyProfileThatIsNotRgb() {
    for (picture in IccPictures.ALL) {
      val expected = if (picture == IccPictures.GREY) "GRAY" else "RGB "

      assertEquals(expected, picture.profile.textAt(COLOUR_SPACE_OFFSET), "$picture colour space")
    }
  }

  @Test
  fun everyTagSitsAlignedInsideItsProfile() {
    for (picture in IccPictures.ALL) {
      val profile = picture.profile
      val tags = tagsOf(profile)

      assertTrue("desc" in tags && "cprt" in tags && "wtpt" in tags, "$picture required tags")
      for ((signature, place) in tags) {
        val (offset, size) = place
        assertEquals(0, offset % 4, "$picture $signature alignment")
        assertTrue(offset + size <= profile.size, "$picture $signature bounds")
      }
    }
  }

  @Test
  fun matrixProfilesCarryColorantsAndCurvesAndTheLutCarriesATable() {
    val matrix = listOf("rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC")

    for (picture in listOf(IccPictures.SRGB, IccPictures.LINEAR_SRGB, IccPictures.DISPLAY_P3)) {
      assertTrue(tagsOf(picture.profile).keys.containsAll(matrix), "$picture matrix tags")
    }
    assertEquals("mAB ", IccPictures.LUT_SRGB.profile.tagType("A2B0"))
    assertEquals("curv", IccPictures.GREY.profile.tagType("kTRC"))
    assertEquals("para", IccPictures.SRGB.profile.tagType("rTRC"))
  }

  @Test
  fun greyCarriesItsGammaAsOneU8Fixed8CurveEntry() {
    val profile = IccPictures.GREY.profile
    val curve = tagsOf(profile).getValue("kTRC").first

    assertEquals(1, profile.int32At(curve + CURVE_COUNT_OFFSET))
    assertEquals(GREY_GAMMA_U8_FIXED8, profile.uInt16At(curve + CURVE_ENTRIES_OFFSET))
  }

  @Test
  fun everyRgbProfileCarriesAChromaticAdaptationAndGreyDoesNot() {
    for (picture in IccPictures.ALL - IccPictures.GREY) {
      assertEquals("sf32", picture.profile.tagType("chad"), "$picture")
    }
    assertTrue("chad" !in tagsOf(IccPictures.GREY.profile), "grey")
  }

  @Test
  fun aTaggedPngEmbedsItsProfileStraightAfterTheHeader() {
    for (picture in IccPictures.ALL) {
      val chunks = chunksOf(picture.pngOf(SIZE))

      assertEquals(listOf("IHDR", "iCCP", "IDAT", "IEND"), chunks.map { it.first }, "$picture chunks")
      val iccp = chunks[1].second
      val nameEnd = iccp.indexOf(0)
      assertEquals(0, iccp[nameEnd + 1].toInt(), "$picture compression method")
      assertContentEquals(picture.profile, storedInflate(iccp.copyOfRange(nameEnd + 2, iccp.size)), "$picture profile")
    }
  }

  @Test
  fun everyTaggedPngReadsBackTheLightOfItsProfile() {
    for (picture in IccPictures.ALL) {
      val embedded = assertNotNull(pictureColour(picture.pngOf(SIZE)), "$picture embedded colour")
      val channels = if (picture === IccPictures.GREY) 1 else 3
      val profile = assertNotNull(PictureColour.fromDecodedProfile(picture.profile, channels), "$picture profile")
      val texels = intArrayOf(picture.texel)

      assertContentEquals(pictureLight(profile, texels), pictureLight(embedded, texels), "$picture light")
    }
  }

  @Test
  fun anUntaggedPngHasNoProfileChunk() {
    val chunks = chunksOf(OverlayPixels.pngOf(SIZE, AuthoredColours.MID_GREY))

    assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.first })
  }

  @Test
  fun anRgbPictureIsPaintedWithItsTexelAsRgba() {
    val picture = IccPictures.DISPLAY_P3
    val header = chunksOf(picture.pngOf(SIZE)).first().second
    val rows = storedInflate(chunksOf(picture.pngOf(SIZE)).single { it.first == "IDAT" }.second)

    assertEquals(RGBA, header[COLOUR_TYPE_OFFSET].toInt())
    assertEquals(SIZE.height * (SIZE.width * 4 + 1), rows.size)
    assertEquals(AuthoredColours.channelsOf(picture.texel) + 0xFF, rows.copyOfRange(1, 5).map { it.toInt() and 0xFF })
  }

  @Test
  fun aGreyPictureIsPaintedWithItsTexelAsGreyAndAlpha() {
    val picture = IccPictures.GREY
    val header = chunksOf(picture.pngOf(SIZE)).first().second
    val rows = storedInflate(chunksOf(picture.pngOf(SIZE)).single { it.first == "IDAT" }.second)

    assertEquals(GREY_ALPHA, header[COLOUR_TYPE_OFFSET].toInt())
    assertEquals(SIZE.height * (SIZE.width * 2 + 1), rows.size)
    assertEquals(listOf(picture.texel and 0xFF, 0xFF), rows.copyOfRange(1, 3).map { it.toInt() and 0xFF })
  }

  @Test
  fun aGreyProfileRefusesAColouredTexel() {
    assertFailsWith<IllegalArgumentException> {
      OverlayPixels.pngOf(SIZE, AuthoredColours.MID_GREEN, IccPictures.GREY.profile)
    }
  }

  private fun tagsOf(profile: ByteArray): Map<String, Pair<Int, Int>> =
    (0 until profile.int32At(HEADER_BYTES)).associate { index ->
      val entry = HEADER_BYTES + 4 + index * TAG_ENTRY_BYTES
      profile.textAt(entry) to (profile.int32At(entry + 4) to profile.int32At(entry + 8))
    }

  private fun ByteArray.tagType(signature: String): String = textAt(tagsOf(this).getValue(signature).first)

  private fun chunksOf(png: ByteArray): List<Pair<String, ByteArray>> {
    val chunks = mutableListOf<Pair<String, ByteArray>>()
    var offset = PNG_SIGNATURE_BYTES
    while (offset < png.size) {
      val length = png.int32At(offset)
      chunks += png.textAt(offset + 4) to png.copyOfRange(offset + 8, offset + 8 + length)
      offset += length + CHUNK_OVERHEAD
    }
    return chunks
  }

  // Joins the blocks of a zlib stream that holds every block stored, the only kind pngOf writes.
  private fun storedInflate(zlib: ByteArray): ByteArray {
    var inflated = ByteArray(0)
    var offset = 2
    do {
      val last = zlib[offset].toInt() and 1
      val length = (zlib[offset + 1].toInt() and 0xFF) or (zlib[offset + 2].toInt() and 0xFF shl 8)
      inflated += zlib.copyOfRange(offset + 5, offset + 5 + length)
      offset += 5 + length
    } while (last == 0)
    return inflated
  }

  private fun ByteArray.textAt(offset: Int): String = decodeToString(offset, offset + 4)

  private fun ByteArray.int32At(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 24) or
      (this[offset + 1].toInt() and 0xFF shl 16) or
      (this[offset + 2].toInt() and 0xFF shl 8) or
      (this[offset + 3].toInt() and 0xFF)

  private fun ByteArray.uInt16At(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 8) or (this[offset + 1].toInt() and 0xFF)

  private companion object {
    val SIZE = Size(6, 2)

    const val COLOUR_SPACE_OFFSET = 16
    const val PCS_OFFSET = 20
    const val SIGNATURE_OFFSET = 36
    const val VERSION_OFFSET = 8
    const val VERSION_4_3 = 0x04300000
    const val HEADER_BYTES = 128
    const val TAG_ENTRY_BYTES = 12

    // A curv's entry count and first entry, after its type and reserved bytes.
    const val CURVE_COUNT_OFFSET = 8
    const val CURVE_ENTRIES_OFFSET = 12

    // 563/256, the u8Fixed8 nearest 2.2.
    const val GREY_GAMMA_U8_FIXED8 = 0x0233

    const val PNG_SIGNATURE_BYTES = 8

    // A chunk's length, type and checksum around its data.
    const val CHUNK_OVERHEAD = 12

    // The colour type byte inside the IHDR data.
    const val COLOUR_TYPE_OFFSET = 9
    const val RGBA = 6
    const val GREY_ALPHA = 4
  }
}
