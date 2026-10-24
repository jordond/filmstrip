package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.pictureSrgbCodes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shape of the pictures described by PNG colour chunks and by HEIF `colr` boxes.
 *
 * Each PNG is walked chunk by chunk, every checksum is worked again from a lookup table, every colour chunk is read
 * back field by field and the image data is inflated to the texel. Each AVIF and HEIC is walked box by box down to its
 * item properties, and its `iloc` extent must land on the codestream inside `mdat`. The HEVC configuration is walked
 * down to its parameter sets, and core reads its sequence parameter set as far as the range and colour it signals.
 */
class ColourPicturesTest {
  @Test
  fun everyPngWritesItsColourChunksBetweenTheHeaderAndTheImageData() {
    val expected =
      mapOf(
        ColourPictures.CICP_DISPLAY_P3 to listOf("cICP"),
        ColourPictures.CICP_BT709 to listOf("cICP"),
        ColourPictures.CICP_LINEAR to listOf("cICP"),
        ColourPictures.GAMMA_LINEAR to listOf("gAMA"),
        ColourPictures.GAMMA_SRGB to listOf("gAMA", "cHRM"),
        ColourPictures.GAMMA_DISPLAY_P3 to listOf("gAMA", "cHRM"),
        ColourPictures.CHROMATICITIES_DISPLAY_P3 to listOf("cHRM"),
        ColourPictures.SRGB_CHUNK to listOf("sRGB"),
        ColourPictures.PRECEDENCE to listOf("cICP", "iCCP", "gAMA"),
        ColourPictures.ICCP_OVER_GAMMA to listOf("iCCP", "gAMA"),
        ColourPictures.SRGB_OVER_GAMMA to listOf("sRGB", "gAMA"),
      )

    assertEquals(ColourPictures.PNGS.toSet(), expected.keys)
    for ((picture, colourChunks) in expected) {
      val types = pngChunks(picture.pngOf(SIZE)).map { it.type }

      assertEquals(listOf("IHDR") + colourChunks + listOf("IDAT", "IEND"), types, "$picture chunks")
    }
    for (picture in ColourPictures.HDR_PNGS) {
      assertEquals(listOf("IHDR", "cICP", "IDAT", "IEND"), pngChunks(picture.pngOf(SIZE)).map { it.type }, "$picture")
    }
  }

  @Test
  fun everyChunkChecksumCoversItsTypeAndData() {
    val pngs = ColourPictures.PNGS.map { it.pngOf(SIZE) } + ColourPictures.HDR_PNGS.map { it.pngOf(SIZE) }

    for ((index, png) in pngs.withIndex()) {
      for (chunk in pngChunks(png)) {
        assertEquals(tableCrc32(chunk.type.encodeToByteArray() + chunk.data), chunk.crc, "$index ${chunk.type}")
      }
    }
  }

  @Test
  fun anSrgbChunkAndASrgbGammaMatchTheirPublishedBytes() {
    val srgb = ColourPictures.SRGB_CHUNK.pngOf(SIZE)
    val gamma = ColourPictures.GAMMA_SRGB.pngOf(SIZE)

    assertContentEquals(SRGB_CHUNK, srgb.copyOfRange(COLOUR_CHUNK_OFFSET, COLOUR_CHUNK_OFFSET + SRGB_CHUNK.size))
    assertContentEquals(
      SRGB_GAMA_CHUNK,
      gamma.copyOfRange(COLOUR_CHUNK_OFFSET, COLOUR_CHUNK_OFFSET + SRGB_GAMA_CHUNK.size),
    )
  }

  @Test
  fun everyColourChunkHoldsTheFieldsItWasGiven() {
    for (picture in ColourPictures.PNGS) {
      val chunks = pngChunks(picture.pngOf(SIZE)).associateBy { it.type }
      val colour = picture.colour

      assertEquals(colour.cicp?.let(::cicpBytes), chunks["cICP"]?.data?.map { it.toInt() and 0xFF }, "$picture cICP")
      assertEquals(colour.gamma, chunks["gAMA"]?.data?.int32At(0), "$picture gAMA")
      assertEquals(colour.srgbIntent, chunks["sRGB"]?.data?.uInt8At(0), "$picture sRGB")
      val chromaticities = chunks["cHRM"]?.data?.let { cHRM -> List(8) { cHRM.int32At(it * 4) } }
      assertEquals(colour.chromaticities?.values, chromaticities, "$picture cHRM")
      if (picture.profile == null) {
        assertNull(chunks["iCCP"], "$picture iCCP")
      } else {
        val iccp = chunks.getValue("iCCP").data
        val profileAt = "ICC profile".length + 2
        assertContentEquals(picture.profile, storedInflate(iccp.copyOfRange(profileAt, iccp.size)), "$picture profile")
      }
    }
  }

  @Test
  fun theCicpPicturesCarryTheirCodePoints() {
    assertEquals(listOf(12, 13, 0, 1), cicpBytes(ColourPictures.CICP_DISPLAY_P3.colour.cicp!!))
    assertEquals(listOf(1, 1, 0, 1), cicpBytes(ColourPictures.CICP_BT709.colour.cicp!!))
    assertEquals(listOf(1, 8, 0, 1), cicpBytes(ColourPictures.CICP_LINEAR.colour.cicp!!))
    assertEquals(listOf(9, 16, 0, 1), cicpBytes(ColourPictures.CICP_PQ.cicp))
    assertEquals(listOf(9, 18, 0, 1), cicpBytes(ColourPictures.CICP_HLG.cicp))
  }

  @Test
  fun everyEightBitPngStoresItsTexelAtEveryPixel() {
    for (picture in ColourPictures.PNGS) {
      val chunks = pngChunks(picture.pngOf(SIZE))
      val header = chunks.first().data
      val rows = storedInflate(chunks.single { it.type == "IDAT" }.data)
      val texel = AuthoredColours.channelsOf(picture.texel) + (picture.texel ushr 24)

      assertEquals(listOf(8, RGBA), listOf(header.uInt8At(BIT_DEPTH), header.uInt8At(COLOUR_TYPE)), "$picture")
      assertEquals(SIZE.height * (SIZE.width * 4 + 1), rows.size, "$picture rows")
      for (row in 0 until SIZE.height) {
        val start = row * (SIZE.width * 4 + 1)
        assertEquals(0, rows.uInt8At(start), "$picture filter")
        val pixels = (0 until SIZE.width * 4).map { rows.uInt8At(start + 1 + it) }
        assertEquals(List(SIZE.width) { texel }.flatten(), pixels, "$picture row $row")
      }
    }
  }

  @Test
  fun everyHdrPngStoresItsSixteenBitTexel() {
    for (picture in ColourPictures.HDR_PNGS) {
      val chunks = pngChunks(picture.pngOf(SIZE))
      val header = chunks.first().data
      val rows = storedInflate(chunks.single { it.type == "IDAT" }.data)
      val stride = SIZE.width * 8 + 1

      assertEquals(listOf(16, RGBA), listOf(header.uInt8At(BIT_DEPTH), header.uInt8At(COLOUR_TYPE)), "$picture")
      assertEquals(SIZE.height * stride, rows.size, "$picture rows")
      val last = (SIZE.height - 1) * stride + 1 + (SIZE.width - 1) * 8
      assertEquals(picture.texel, List(4) { rows.uInt16At(last + it * 2) }, "$picture last pixel")
    }
  }

  @Test
  fun theTexelsSitMidRange() {
    val inside = AuthoredColours.MID_RANGE.first + 1 until AuthoredColours.MID_RANGE.last
    val channels = AuthoredColours.channelsOf(ColourPictures.TEXEL)

    assertTrue(channels.all { it in inside }, "$channels inside $inside")
    assertEquals(3, channels.toSet().size, "$channels distinct")
    for (picture in ColourPictures.HDR_PNGS) {
      assertTrue(picture.texel.take(3).all { it in SIXTEEN_BIT_MID_RANGE }, "$picture")
    }
  }

  @Test
  fun refusesASixteenBitTexelOutOfRange() {
    assertFailsWith<IllegalArgumentException> { OverlayPixels.sixteenBitPngOf(SIZE, listOf(0, 0, 0x10000, 0)) }
    assertFailsWith<IllegalArgumentException> { OverlayPixels.sixteenBitPngOf(SIZE, listOf(0, 0, 0)) }
  }

  @Test
  fun everyDescriptionOfOnePictureGivesTheTexelADifferentColour() {
    val descriptions =
      mapOf(
        ColourPictures.PRECEDENCE to 3,
        ColourPictures.ICCP_OVER_GAMMA to 2,
        ColourPictures.SRGB_OVER_GAMMA to 2,
      )

    for ((picture, count) in descriptions) {
      val readings = singleDescriptions(picture).map(::srgbCodesOf)

      assertEquals(count, readings.size, "$picture descriptions")
      assertEquals(readings.size, readings.toSet().size, "$picture reads $readings")
      assertEquals(readings.first(), srgbCodesOf(picture), "$picture reads its first description")
    }
  }

  @Test
  fun everyStillIsFtypThenMetaThenMdat() {
    for (still in stills()) {
      val bytes = still.bytes
      val boxes = heifBoxes(bytes)
      val ftyp = boxes.named("ftyp")

      assertEquals(listOf("ftyp", "meta", "mdat"), boxes.map { it.type }, "$still")
      assertEquals(still.brand, bytes.textAt(ftyp.start), "$still major brand")
      assertEquals(listOf(still.brand, "mif1", "miaf"), (ftyp.start + 8 until ftyp.end step 4).map { bytes.textAt(it) })
      val meta = bytes.childrenOf(boxes.named("meta"), fullBox = true)
      assertEquals(listOf("hdlr", "pitm", "iloc", "iinf", "iprp"), meta.map { it.type }, "$still meta")
      assertEquals("pict", bytes.textAt(meta.named("hdlr").start + 8), "$still handler")
    }
  }

  @Test
  fun everyStillLocatesItsPrimaryItemOnTheCodestreamInMdat() {
    for (still in stills()) {
      val bytes = still.bytes
      val boxes = heifBoxes(bytes)
      val meta = bytes.childrenOf(boxes.named("meta"), fullBox = true)
      val primary = bytes.uInt16At(meta.named("pitm").start + 4)
      val iloc = meta.named("iloc").start + 4
      val iinf = meta.named("iinf")
      val offset = bytes.int32At(iloc + 10)
      val length = bytes.int32At(iloc + 14)

      assertEquals(listOf(0x44, 0, 1), listOf(bytes.uInt8At(iloc), bytes.uInt8At(iloc + 1), bytes.uInt16At(iloc + 2)))
      assertEquals(listOf(primary, 0, 1), listOf(iloc + 4, iloc + 6, iloc + 8).map { bytes.uInt16At(it) })
      assertEquals(boxes.named("mdat").start, offset, "$still extent offset")
      assertEquals(boxes.named("mdat").end - offset, length, "$still extent length")
      assertContentEquals(still.codestream, bytes.copyOfRange(offset, offset + length), "$still codestream")
      // Version and flags, then a two byte entry count ahead of the item entries.
      val item = heifBoxes(bytes, iinf.start + 6, iinf.end).single()
      assertEquals(2, bytes.uInt8At(item.start), "$still infe version")
      assertEquals(primary, bytes.uInt16At(item.start + 4), "$still infe item")
      assertEquals(still.itemType, bytes.textAt(item.start + 8), "$still item type")
    }
  }

  @Test
  fun everyStillAssociatesItsPropertiesWithTheConfigurationEssential() {
    for (still in stills()) {
      val bytes = still.bytes
      val iprp = bytes.childrenOf(bytes.childrenOf(heifBoxes(bytes).named("meta"), fullBox = true).named("iprp"))
      val properties = bytes.childrenOf(iprp.named("ipco"))
      val ipma = iprp.named("ipma").start + 4
      val count = bytes.uInt8At(ipma + 6)
      val colr = listOfNotNull(still.profile?.let { "colr" }, still.nclx?.let { "colr" })
      val configuration = properties[2]

      assertEquals(listOf("ispe", "pixi", still.configurationType) + colr, properties.map { it.type }, "$still")
      assertEquals(listOf(1, 1), listOf(bytes.int32At(ipma), bytes.uInt16At(ipma + 4)), "$still ipma entry")
      val associations = (0 until count).map { bytes.uInt8At(ipma + 7 + it) }
      assertEquals(properties.indices.map { if (it == 2) 0x80 or 3 else it + 1 }, associations, "$still ipma")
      val ispe = properties[0].start
      val size = listOf(still.size.width, still.size.height)
      assertEquals(size, listOf(bytes.int32At(ispe + 4), bytes.int32At(ispe + 8)), "$still ispe")
      assertEquals(listOf(3, 8, 8, 8), (0 until 4).map { bytes.uInt8At(properties[1].start + 4 + it) }, "$still pixi")
      assertContentEquals(still.configuration, bytes.copyOfRange(configuration.start, configuration.end), "$still")
    }
  }

  @Test
  fun everyColrBoxHoldsTheFieldsItWasGiven() {
    for (still in stills()) {
      val bytes = still.bytes
      val iprp = bytes.childrenOf(bytes.childrenOf(heifBoxes(bytes).named("meta"), fullBox = true).named("iprp"))
      val colrs =
        bytes.childrenOf(iprp.named("ipco")).filter { it.type == "colr" }.associateBy { bytes.textAt(it.start) }
      val expected = listOfNotNull(still.profile?.let { "prof" }, still.nclx?.let { "nclx" })

      assertEquals(expected, colrs.keys.toList(), "$still colr order")
      still.nclx?.let { cicp ->
        val nclx = colrs.getValue("nclx")
        assertEquals(nclx.start + 11, nclx.end, "$still nclx size")
        assertEquals(
          listOf(cicp.primaries, cicp.transfer, cicp.matrix, if (cicp.fullRange) 0x80 else 0),
          listOf(4, 6, 8).map { bytes.uInt16At(nclx.start + it) } + bytes.uInt8At(nclx.start + 10),
          "$still nclx",
        )
      }
      colrs["prof"]?.let { prof ->
        assertContentEquals(still.profile, bytes.copyOfRange(prof.start + 4, prof.end), "$still prof")
      }
    }
  }

  @Test
  fun theStillsCarryTheColourBoxesEachPictureNames() {
    val expected =
      mapOf(
        ColourPictures.NCLX_DISPLAY_P3 to "12/13/6 full",
        ColourPictures.NCLX_DISPLAY_P3_WITH_PROFILE to "12/13/6 full",
        ColourPictures.NCLX_BT709_LIMITED to "1/13/1 limited",
        ColourPictures.NCLX_UNSPECIFIED to "2/2/6 full",
        ColourPictures.PROFILE_DISPLAY_P3 to null,
        ColourPictures.HEIC_NCLX_BT709_FULL to "1/13/1 full",
        ColourPictures.HEIC_NCLX_BT601_FULL to "1/13/6 full",
        ColourPictures.HEIC_NCLX_BT709_LIMITED to "1/13/1 limited",
        ColourPictures.HEIC_PROFILE_DISPLAY_P3 to null,
        ColourPictures.HEIC_NCLX_LIMITED_VUI_FULL to "1/13/1 limited",
        ColourPictures.HEIC_NCLX_BT601_VUI_BT709 to "1/13/6 full",
        ColourPictures.HEIC_UNTAGGED to null,
        ColourPictures.HEIC_NCLX_BT709_VUI_DISPLAY_P3 to "1/13/1 full",
      )
    val profiles =
      mapOf(
        ColourPictures.NCLX_DISPLAY_P3_WITH_PROFILE to IccPictures.LINEAR_SRGB.profile,
        ColourPictures.PROFILE_DISPLAY_P3 to IccPictures.DISPLAY_P3.profile,
        ColourPictures.HEIC_PROFILE_DISPLAY_P3 to IccPictures.DISPLAY_P3.profile,
      )

    assertEquals((ColourPictures.AVIFS + heics()).toSet(), expected.keys)
    for (still in stills()) {
      assertEquals(expected.getValue(still.picture), still.nclx?.toString(), "$still")
      assertContentEquals(profiles[still.picture], still.profile, "$still profile")
      assertEquals(listOf(128, 96, 176), still.ycbcr, "$still samples")
    }
  }

  @Test
  fun theAv1SequenceHeadersRangeBitMatchesTheNclxRange() {
    for (picture in ColourPictures.AVIFS) {
      val codestream = picture.codestream()

      // The sequence header OBU and its size come first, and the colour range is the second highest bit of its
      // last byte.
      assertEquals(listOf(SEQUENCE_HEADER_OBU, 5), listOf(codestream.uInt8At(0), codestream.uInt8At(1)), "$picture")
      assertEquals(picture.nclx?.fullRange ?: true, codestream.uInt8At(6) and 0x40 != 0, "$picture range")
    }
  }

  @Test
  fun theHevcConfigurationIsMainStillPictureFourTwoZeroEightBitWithOneOfEachParameterSet() {
    for (picture in heics()) {
      val hvcC = picture.configuration()
      val arrays = parameterSets(hvcC)

      assertEquals(listOf(1, MAIN_STILL_PICTURE), listOf(hvcC.uInt8At(0), hvcC.uInt8At(1) and 0x1F), "$picture")
      assertEquals(MAIN_STILL_PICTURE, arrays.single { it.first == SPS }.second.uInt8At(3) and 0x1F, "$picture SPS")
      assertEquals(CHROMA_420, hvcC.uInt8At(16) and 0x03, "$picture chroma format")
      assertEquals(listOf(0, 0), listOf(hvcC.uInt8At(17) and 0x07, hvcC.uInt8At(18) and 0x07), "$picture bit depth")
      assertEquals(3, hvcC.uInt8At(21) and 0x03, "$picture four byte lengths")
      assertEquals(listOf(VPS, SPS, PPS), arrays.map { it.first }, "$picture arrays")
      for ((type, unit) in arrays) {
        assertEquals(type, unit.uInt8At(0) ushr 1 and 0x3F, "$picture NAL unit $type")
      }
    }
  }

  @Test
  fun theHevcCodestreamIsOneIdrSliceBehindItsLength() {
    for (picture in heics()) {
      val codestream = picture.codestream()

      assertEquals(codestream.size - 4, codestream.int32At(0), "$picture length")
      assertEquals(IDR_N_LP, codestream.uInt8At(4) ushr 1 and 0x3F, "$picture NAL unit type")
    }
  }

  @Test
  fun everyHevcSequenceParameterSetSignalsWhatItsVuiNames() {
    for (picture in heics()) {
      val vui = picture.vui
      val expected =
        CodestreamSignal(
          vui.colour?.primaries,
          vui.colour?.transfer,
          vui.colour?.matrix,
          vui.fullRange.takeIf { vui.videoSignalType },
        )
      // Core reads the codestream only beside an nclx, so the picture is tagged again around its own configuration.
      val tagged =
        HeifStills.still(
          brand = "heic",
          itemType = "hvc1",
          configuration = HeifStills.box("hvcC", picture.configuration()),
          codestream = picture.codestream(),
          size = HEVC_SIZE,
          nclx = picture.nclx ?: CicpTag(1, 13, 6, true),
        )

      assertEquals(expected, pictureYcbcrCoding(tagged)?.codestream, "$picture codestream signal")
      if (picture.nclx != null) assertEquals(expected, pictureYcbcrCoding(picture.bytes())?.codestream, "$picture")
    }
    for (picture in ColourPictures.HEICS + ColourPictures.HEIC_UNTAGGED) {
      assertEquals(HevcVui.Unsignalled, picture.vui, "$picture")
    }
  }

  @Test
  fun theSignalVariantsDisagreeWithTheirNclxWhereTheirNameSays() {
    val range = ColourPictures.HEIC_NCLX_LIMITED_VUI_FULL
    assertEquals(listOf(false, true), listOf(range.nclx?.fullRange, range.vui.fullRange), "$range")
    assertEquals(null, range.vui.colour, "$range colour description")
    val matrix = ColourPictures.HEIC_NCLX_BT601_VUI_BT709
    assertEquals(listOf(6, 1), listOf(matrix.nclx?.matrix, matrix.vui.colour?.matrix), "$matrix")
    assertEquals(listOf(true, true), listOf(matrix.nclx?.fullRange, matrix.vui.fullRange), "$matrix range")
    val untagged = ColourPictures.HEIC_UNTAGGED
    assertEquals(listOf(null, null), listOf(untagged.nclx, untagged.profile), "$untagged")
  }

  private fun parameterSets(hvcC: ByteArray): List<Pair<Int, ByteArray>> {
    var offset = HVCC_ARRAYS_OFFSET
    return List(hvcC.uInt8At(offset - 1)) {
      val type = hvcC.uInt8At(offset) and 0x3F
      assertEquals(1, hvcC.uInt16At(offset + 1), "one NAL unit of type $type")
      val length = hvcC.uInt16At(offset + 3)
      val unit = hvcC.copyOfRange(offset + 5, offset + 5 + length)
      offset += 5 + length
      type to unit
    }.also { assertEquals(hvcC.size, offset, "hvcC length") }
  }

  /**
   * One picture per colour description [picture] carries, each holding only that description, in the order a reader
   * prefers them.
   */
  private fun singleDescriptions(picture: PngPicture): List<PngPicture> {
    val colour = picture.colour
    val gamma = PngColour(gamma = colour.gamma, chromaticities = colour.chromaticities)
    val hasGamma = colour.gamma != null || colour.chromaticities != null
    return listOfNotNull(
      colour.cicp?.let { PngPicture("$picture cICP", PngColour(cicp = it), null, picture.texel) },
      picture.profile?.let { PngPicture("$picture iCCP", PngColour(), it, picture.texel) },
      colour.srgbIntent?.let { PngPicture("$picture sRGB", PngColour(srgbIntent = it), null, picture.texel) },
      if (hasGamma) PngPicture("$picture gAMA", gamma, null, picture.texel) else null,
    )
  }

  private fun srgbCodesOf(picture: PngPicture): List<Int> =
    pictureSrgbCodes(pictureColour(picture.pngOf(SIZE)), intArrayOf(picture.texel)).toList()

  private class Still(
    val picture: Any,
    val brand: String,
    val itemType: String,
    val configurationType: String,
    val configuration: ByteArray,
    val size: Size,
    val nclx: CicpTag?,
    val profile: ByteArray?,
    val codestream: ByteArray,
    val bytes: ByteArray,
    val ycbcr: List<Int>,
  ) {
    override fun toString(): String = "$picture"
  }

  private fun stills(): List<Still> =
    ColourPictures.AVIFS.map { avif ->
      Still(
        picture = avif,
        brand = "avif",
        itemType = "av01",
        configurationType = "av1C",
        configuration = AV1_CONFIGURATION,
        size = AV1_SIZE,
        nclx = avif.nclx,
        profile = avif.profile,
        codestream = avif.codestream(),
        bytes = avif.bytes(),
        ycbcr = avif.ycbcr,
      )
    } +
      heics().map { heic ->
        Still(
          picture = heic,
          brand = "heic",
          itemType = "hvc1",
          configurationType = "hvcC",
          configuration = heic.configuration(),
          size = HEVC_SIZE,
          nclx = heic.nclx,
          profile = heic.profile,
          codestream = heic.codestream(),
          bytes = heic.bytes(),
          ycbcr = heic.ycbcr,
        )
      }

  private fun heics(): List<HeicPicture> = ColourPictures.HEICS_WITH_SIGNAL_VARIANTS

  private fun cicpBytes(cicp: CicpTag): List<Int> =
    listOf(cicp.primaries, cicp.transfer, cicp.matrix, if (cicp.fullRange) 1 else 0)

  private companion object {
    val SIZE = Size(5, 3)

    // The signature and the IHDR chunk come first.
    const val COLOUR_CHUNK_OFFSET = 33
    const val BIT_DEPTH = 8
    const val COLOUR_TYPE = 9
    const val RGBA = 6
    const val SEQUENCE_HEADER_OBU = 0x0A
    val AV1_SIZE = Size(8, 8)
    val HEVC_SIZE = Size(64, 64)

    // The hvcC fields ahead of its parameter set arrays, and the HEVC codes it and the codestream carry.
    const val HVCC_ARRAYS_OFFSET = 23
    const val MAIN_STILL_PICTURE = 3
    const val CHROMA_420 = 1
    const val VPS = 32
    const val SPS = 33
    const val PPS = 34
    const val IDR_N_LP = 20
    val SIXTEEN_BIT_MID_RANGE = 0x4000..0xC000

    // An sRGB chunk with the perceptual intent, and a gAMA of 45455, as every PNG writer emits them.
    val SRGB_CHUNK = hex("0000000173524742" + "00" + "aece1ce9")
    val SRGB_GAMA_CHUNK = hex("0000000467414d41" + "0000b18f" + "0bfc6105")

    val AV1_CONFIGURATION = byteArrayOf(0x81.toByte(), 0, 0x0C, 0)

    fun hex(digits: String): ByteArray = digits.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
