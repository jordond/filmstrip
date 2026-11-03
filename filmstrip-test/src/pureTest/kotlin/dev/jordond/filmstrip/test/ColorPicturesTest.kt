package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The shape of the pictures described by PNG color chunks and by HEIF `colr` boxes.
 *
 * Each PNG is walked chunk by chunk, every checksum is worked again from a lookup table, every color chunk is read
 * back field by field and the image data is inflated to the texel. Each AVIF and HEIC is walked box by box down to its
 * item properties, and its `iloc` extent must land on the codestream inside `mdat`. The HEVC configuration is walked
 * down to its parameter sets, and core reads its sequence parameter set as far as the range and color it signals.
 */
class ColorPicturesTest {
  @Test
  fun everyPngWritesItsColorChunksBetweenTheHeaderAndTheImageData() {
    val expected =
      mapOf(
        ColorPictures.CICP_DISPLAY_P3 to listOf("cICP"),
        ColorPictures.CICP_BT709 to listOf("cICP"),
        ColorPictures.CICP_LINEAR to listOf("cICP"),
        ColorPictures.GAMMA_LINEAR to listOf("gAMA"),
        ColorPictures.GAMMA_SRGB to listOf("gAMA", "cHRM"),
        ColorPictures.GAMMA_DISPLAY_P3 to listOf("gAMA", "cHRM"),
        ColorPictures.CHROMATICITIES_DISPLAY_P3 to listOf("cHRM"),
        ColorPictures.SRGB_CHUNK to listOf("sRGB"),
        ColorPictures.PRECEDENCE to listOf("cICP", "iCCP", "gAMA"),
        ColorPictures.ICCP_OVER_GAMMA to listOf("iCCP", "gAMA"),
        ColorPictures.SRGB_OVER_GAMMA to listOf("sRGB", "gAMA"),
      )

    expected.keys shouldBe ColorPictures.PNGS.toSet()
    for ((picture, colorChunks) in expected) {
      val types = pngChunks(picture.pngOf(SIZE)).map { it.type }

      withClue("$picture chunks") { types shouldBe listOf("IHDR") + colorChunks + listOf("IDAT", "IEND") }
    }
    for (picture in ColorPictures.HDR_PNGS) {
      withClue("$picture") {
        pngChunks(picture.pngOf(SIZE)).map { it.type } shouldBe listOf("IHDR", "cICP", "IDAT", "IEND")
      }
    }
  }

  @Test
  fun everyChunkChecksumCoversItsTypeAndData() {
    val pngs = ColorPictures.PNGS.map { it.pngOf(SIZE) } + ColorPictures.HDR_PNGS.map { it.pngOf(SIZE) }

    for ((index, png) in pngs.withIndex()) {
      for (chunk in pngChunks(png)) {
        withClue("$index ${chunk.type}") { chunk.crc shouldBe tableCrc32(chunk.type.encodeToByteArray() + chunk.data) }
      }
    }
  }

  @Test
  fun anSrgbChunkAndASrgbGammaMatchTheirPublishedBytes() {
    val srgb = ColorPictures.SRGB_CHUNK.pngOf(SIZE)
    val gamma = ColorPictures.GAMMA_SRGB.pngOf(SIZE)

    srgb.copyOfRange(COLOR_CHUNK_OFFSET, COLOR_CHUNK_OFFSET + SRGB_CHUNK.size) shouldBe SRGB_CHUNK
    gamma.copyOfRange(COLOR_CHUNK_OFFSET, COLOR_CHUNK_OFFSET + SRGB_GAMA_CHUNK.size) shouldBe SRGB_GAMA_CHUNK
  }

  @Test
  fun everyColorChunkHoldsTheFieldsItWasGiven() {
    for (picture in ColorPictures.PNGS) {
      val chunks = pngChunks(picture.pngOf(SIZE)).associateBy { it.type }
      val color = picture.color

      withClue("$picture cICP") {
        chunks["cICP"]?.data?.map { it.toInt() and 0xFF } shouldBe color.cicp?.let(::cicpBytes)
      }
      withClue("$picture gAMA") { chunks["gAMA"]?.data?.int32At(0) shouldBe color.gamma }
      withClue("$picture sRGB") { chunks["sRGB"]?.data?.uInt8At(0) shouldBe color.srgbIntent }
      val chromaticities = chunks["cHRM"]?.data?.let { cHRM -> List(8) { cHRM.int32At(it * 4) } }
      withClue("$picture cHRM") { chromaticities shouldBe color.chromaticities?.values }
      if (picture.profile == null) {
        withClue("$picture iCCP") { chunks["iCCP"].shouldBeNull() }
      } else {
        val iccp = chunks.getValue("iCCP").data
        val profileAt = "ICC profile".length + 2
        withClue("$picture profile") { storedInflate(iccp.copyOfRange(profileAt, iccp.size)) shouldBe picture.profile }
      }
    }
  }

  @Test
  fun theCicpPicturesCarryTheirCodePoints() {
    cicpBytes(ColorPictures.CICP_DISPLAY_P3.color.cicp!!) shouldBe listOf(12, 13, 0, 1)
    cicpBytes(ColorPictures.CICP_BT709.color.cicp!!) shouldBe listOf(1, 1, 0, 1)
    cicpBytes(ColorPictures.CICP_LINEAR.color.cicp!!) shouldBe listOf(1, 8, 0, 1)
    cicpBytes(ColorPictures.CICP_PQ.cicp) shouldBe listOf(9, 16, 0, 1)
    cicpBytes(ColorPictures.CICP_HLG.cicp) shouldBe listOf(9, 18, 0, 1)
  }

  @Test
  fun everyEightBitPngStoresItsTexelAtEveryPixel() {
    for (picture in ColorPictures.PNGS) {
      val chunks = pngChunks(picture.pngOf(SIZE))
      val header = chunks.first().data
      val rows = storedInflate(chunks.single { it.type == "IDAT" }.data)
      val texel = AuthoredColors.channelsOf(picture.texel) + (picture.texel ushr 24)

      withClue("$picture") { listOf(header.uInt8At(BIT_DEPTH), header.uInt8At(COLOR_TYPE)) shouldBe listOf(8, RGBA) }
      withClue("$picture rows") { rows.size shouldBe SIZE.height * (SIZE.width * 4 + 1) }
      for (row in 0 until SIZE.height) {
        val start = row * (SIZE.width * 4 + 1)
        withClue("$picture filter") { rows.uInt8At(start) shouldBe 0 }
        val pixels = (0 until SIZE.width * 4).map { rows.uInt8At(start + 1 + it) }
        withClue("$picture row $row") { pixels shouldBe List(SIZE.width) { texel }.flatten() }
      }
    }
  }

  @Test
  fun everyHdrPngStoresItsSixteenBitTexel() {
    for (picture in ColorPictures.HDR_PNGS) {
      val chunks = pngChunks(picture.pngOf(SIZE))
      val header = chunks.first().data
      val rows = storedInflate(chunks.single { it.type == "IDAT" }.data)
      val stride = SIZE.width * 8 + 1

      withClue("$picture") { listOf(header.uInt8At(BIT_DEPTH), header.uInt8At(COLOR_TYPE)) shouldBe listOf(16, RGBA) }
      withClue("$picture rows") { rows.size shouldBe SIZE.height * stride }
      val last = (SIZE.height - 1) * stride + 1 + (SIZE.width - 1) * 8
      withClue("$picture last pixel") { List(4) { rows.uInt16At(last + it * 2) } shouldBe picture.texel }
    }
  }

  @Test
  fun theTexelsSitMidRange() {
    val inside = AuthoredColors.MID_RANGE.first + 1 until AuthoredColors.MID_RANGE.last
    val channels = AuthoredColors.channelsOf(ColorPictures.TEXEL)

    withClue("$channels inside $inside") { channels.all { it in inside } shouldBe true }
    withClue("$channels distinct") { channels.toSet().size shouldBe 3 }
    for (picture in ColorPictures.HDR_PNGS) {
      withClue("$picture") { picture.texel.take(3).all { it in SIXTEEN_BIT_MID_RANGE } shouldBe true }
    }
  }

  @Test
  fun refusesASixteenBitTexelOutOfRange() {
    assertFailsWith<IllegalArgumentException> { OverlayPixels.sixteenBitPngOf(SIZE, listOf(0, 0, 0x10000, 0)) }
    assertFailsWith<IllegalArgumentException> { OverlayPixels.sixteenBitPngOf(SIZE, listOf(0, 0, 0)) }
  }

  @Test
  fun everyDescriptionOfOnePictureGivesTheTexelADifferentColor() {
    val descriptions =
      mapOf(
        ColorPictures.PRECEDENCE to 3,
        ColorPictures.ICCP_OVER_GAMMA to 2,
        ColorPictures.SRGB_OVER_GAMMA to 2,
      )

    for ((picture, count) in descriptions) {
      val readings = singleDescriptions(picture).map(::srgbCodesOf)

      withClue("$picture descriptions") { readings.size shouldBe count }
      withClue("$picture reads $readings") { readings.toSet().size shouldBe readings.size }
      withClue("$picture reads its first description") { srgbCodesOf(picture) shouldBe readings.first() }
    }
  }

  @Test
  fun everyStillIsFtypThenMetaThenMdat() {
    for (still in stills()) {
      val bytes = still.bytes
      val boxes = heifBoxes(bytes)
      val ftyp = boxes.named("ftyp")

      withClue("$still") { boxes.map { it.type } shouldBe listOf("ftyp", "meta", "mdat") }
      withClue("$still major brand") { bytes.textAt(ftyp.start) shouldBe still.brand }
      (ftyp.start + 8 until ftyp.end step 4).map { bytes.textAt(it) } shouldBe listOf(still.brand, "mif1", "miaf")
      val meta = bytes.childrenOf(boxes.named("meta"), fullBox = true)
      withClue("$still meta") { meta.map { it.type } shouldBe listOf("hdlr", "pitm", "iloc", "iinf", "iprp") }
      withClue("$still handler") { bytes.textAt(meta.named("hdlr").start + 8) shouldBe "pict" }
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

      listOf(bytes.uInt8At(iloc), bytes.uInt8At(iloc + 1), bytes.uInt16At(iloc + 2)) shouldBe listOf(0x44, 0, 1)
      listOf(iloc + 4, iloc + 6, iloc + 8).map { bytes.uInt16At(it) } shouldBe listOf(primary, 0, 1)
      withClue("$still extent offset") { offset shouldBe boxes.named("mdat").start }
      withClue("$still extent length") { length shouldBe boxes.named("mdat").end - offset }
      withClue("$still codestream") { bytes.copyOfRange(offset, offset + length) shouldBe still.codestream }
      // Version and flags, then a two byte entry count ahead of the item entries.
      val item = heifBoxes(bytes, iinf.start + 6, iinf.end).single()
      withClue("$still infe version") { bytes.uInt8At(item.start) shouldBe 2 }
      withClue("$still infe item") { bytes.uInt16At(item.start + 4) shouldBe primary }
      withClue("$still item type") { bytes.textAt(item.start + 8) shouldBe still.itemType }
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

      withClue("$still") { properties.map { it.type } shouldBe listOf("ispe", "pixi", still.configurationType) + colr }
      withClue("$still ipma entry") { listOf(bytes.int32At(ipma), bytes.uInt16At(ipma + 4)) shouldBe listOf(1, 1) }
      val associations = (0 until count).map { bytes.uInt8At(ipma + 7 + it) }
      withClue("$still ipma") { associations shouldBe properties.indices.map { if (it == 2) 0x80 or 3 else it + 1 } }
      val ispe = properties[0].start
      val size = listOf(still.size.width, still.size.height)
      withClue("$still ispe") { listOf(bytes.int32At(ispe + 4), bytes.int32At(ispe + 8)) shouldBe size }
      withClue("$still pixi") {
        (0 until 4).map { bytes.uInt8At(properties[1].start + 4 + it) } shouldBe listOf(3, 8, 8, 8)
      }
      withClue("$still") { bytes.copyOfRange(configuration.start, configuration.end) shouldBe still.configuration }
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

      withClue("$still colr order") { colrs.keys.toList() shouldBe expected }
      still.nclx?.let { cicp ->
        val nclx = colrs.getValue("nclx")
        withClue("$still nclx size") { nclx.end shouldBe nclx.start + 11 }
        withClue("$still nclx") {
          listOf(4, 6, 8).map { bytes.uInt16At(nclx.start + it) } + bytes.uInt8At(nclx.start + 10) shouldBe
            listOf(cicp.primaries, cicp.transfer, cicp.matrix, if (cicp.fullRange) 0x80 else 0)
        }
      }
      colrs["prof"]?.let { prof ->
        withClue("$still prof") { bytes.copyOfRange(prof.start + 4, prof.end) shouldBe still.profile }
      }
    }
  }

  @Test
  fun theStillsCarryTheColorBoxesEachPictureNames() {
    val expected =
      mapOf(
        ColorPictures.NCLX_DISPLAY_P3 to "12/13/6 full",
        ColorPictures.NCLX_DISPLAY_P3_WITH_PROFILE to "12/13/6 full",
        ColorPictures.NCLX_BT709_LIMITED to "1/13/1 limited",
        ColorPictures.NCLX_UNSPECIFIED to "2/2/6 full",
        ColorPictures.PROFILE_DISPLAY_P3 to null,
        ColorPictures.HEIC_NCLX_BT709_FULL to "1/13/1 full",
        ColorPictures.HEIC_NCLX_BT601_FULL to "1/13/6 full",
        ColorPictures.HEIC_NCLX_BT709_LIMITED to "1/13/1 limited",
        ColorPictures.HEIC_PROFILE_DISPLAY_P3 to null,
        ColorPictures.HEIC_NCLX_LIMITED_VUI_FULL to "1/13/1 limited",
        ColorPictures.HEIC_NCLX_BT601_VUI_BT709 to "1/13/6 full",
        ColorPictures.HEIC_UNTAGGED to null,
        ColorPictures.HEIC_NCLX_BT709_VUI_DISPLAY_P3 to "1/13/1 full",
      )
    val profiles =
      mapOf(
        ColorPictures.NCLX_DISPLAY_P3_WITH_PROFILE to IccPictures.LINEAR_SRGB.profile,
        ColorPictures.PROFILE_DISPLAY_P3 to IccPictures.DISPLAY_P3.profile,
        ColorPictures.HEIC_PROFILE_DISPLAY_P3 to IccPictures.DISPLAY_P3.profile,
      )

    expected.keys shouldBe (ColorPictures.AVIFS + heics()).toSet()
    for (still in stills()) {
      withClue("$still") { still.nclx?.toString() shouldBe expected.getValue(still.picture) }
      withClue("$still profile") { still.profile shouldBe profiles[still.picture] }
      withClue("$still samples") { still.ycbcr shouldBe listOf(128, 96, 176) }
    }
  }

  @Test
  fun theAv1SequenceHeadersRangeBitMatchesTheNclxRange() {
    for (picture in ColorPictures.AVIFS) {
      val codestream = picture.codestream()

      // The sequence header OBU and its size come first, and the color range is the second highest bit of its
      // last byte.
      withClue("$picture") {
        listOf(codestream.uInt8At(0), codestream.uInt8At(1)) shouldBe listOf(SEQUENCE_HEADER_OBU, 5)
      }
      withClue("$picture range") { (codestream.uInt8At(6) and 0x40 != 0) shouldBe (picture.nclx?.fullRange ?: true) }
    }
  }

  @Test
  fun theHevcConfigurationIsMainStillPictureFourTwoZeroEightBitWithOneOfEachParameterSet() {
    for (picture in heics()) {
      val hvcC = picture.configuration()
      val arrays = parameterSets(hvcC)

      withClue("$picture") { listOf(hvcC.uInt8At(0), hvcC.uInt8At(1) and 0x1F) shouldBe listOf(1, MAIN_STILL_PICTURE) }
      withClue("$picture SPS") {
        (arrays.single { it.first == SPS }.second.uInt8At(3) and 0x1F) shouldBe MAIN_STILL_PICTURE
      }
      withClue("$picture chroma format") { (hvcC.uInt8At(16) and 0x03) shouldBe CHROMA_420 }
      withClue("$picture bit depth") {
        listOf(hvcC.uInt8At(17) and 0x07, hvcC.uInt8At(18) and 0x07) shouldBe listOf(0, 0)
      }
      withClue("$picture four byte lengths") { (hvcC.uInt8At(21) and 0x03) shouldBe 3 }
      withClue("$picture arrays") { arrays.map { it.first } shouldBe listOf(VPS, SPS, PPS) }
      for ((type, unit) in arrays) {
        withClue("$picture NAL unit $type") { (unit.uInt8At(0) ushr 1 and 0x3F) shouldBe type }
      }
    }
  }

  @Test
  fun theHevcCodestreamIsOneIdrSliceBehindItsLength() {
    for (picture in heics()) {
      val codestream = picture.codestream()

      withClue("$picture length") { codestream.int32At(0) shouldBe codestream.size - 4 }
      withClue("$picture NAL unit type") { (codestream.uInt8At(4) ushr 1 and 0x3F) shouldBe IDR_N_LP }
    }
  }

  @Test
  fun everyHevcSequenceParameterSetSignalsWhatItsVuiNames() {
    for (picture in heics()) {
      val vui = picture.vui
      val expected =
        CodestreamSignal(
          vui.color?.primaries,
          vui.color?.transfer,
          vui.color?.matrix,
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

      withClue("$picture codestream signal") { pictureYcbcrCoding(tagged)?.codestream shouldBe expected }
      if (picture.nclx != null) {
        withClue("$picture") {
          pictureYcbcrCoding(picture.bytes())?.codestream shouldBe expected
        }
      }
    }
    for (picture in ColorPictures.HEICS + ColorPictures.HEIC_UNTAGGED) {
      withClue("$picture") { picture.vui shouldBe HevcVui.Unsignalled }
    }
  }

  @Test
  fun theSignalVariantsDisagreeWithTheirNclxWhereTheirNameSays() {
    val range = ColorPictures.HEIC_NCLX_LIMITED_VUI_FULL
    withClue("$range") { listOf(range.nclx?.fullRange, range.vui.fullRange) shouldBe listOf(false, true) }
    withClue("$range color description") { range.vui.color shouldBe null }
    val matrix = ColorPictures.HEIC_NCLX_BT601_VUI_BT709
    withClue("$matrix") { listOf(matrix.nclx?.matrix, matrix.vui.color?.matrix) shouldBe listOf(6, 1) }
    withClue("$matrix range") { listOf(matrix.nclx?.fullRange, matrix.vui.fullRange) shouldBe listOf(true, true) }
    val untagged = ColorPictures.HEIC_UNTAGGED
    withClue("$untagged") { listOf(untagged.nclx, untagged.profile) shouldBe listOf(null, null) }
  }

  private fun parameterSets(hvcC: ByteArray): List<Pair<Int, ByteArray>> {
    var offset = HVCC_ARRAYS_OFFSET
    return List(hvcC.uInt8At(offset - 1)) {
      val type = hvcC.uInt8At(offset) and 0x3F
      withClue("one NAL unit of type $type") { hvcC.uInt16At(offset + 1) shouldBe 1 }
      val length = hvcC.uInt16At(offset + 3)
      val unit = hvcC.copyOfRange(offset + 5, offset + 5 + length)
      offset += 5 + length
      type to unit
    }.also { withClue("hvcC length") { offset shouldBe hvcC.size } }
  }

  /**
   * One picture per color description [picture] carries, each holding only that description, in the order a reader
   * prefers them.
   */
  private fun singleDescriptions(picture: PngPicture): List<PngPicture> {
    val color = picture.color
    val gamma = PngColor(gamma = color.gamma, chromaticities = color.chromaticities)
    val hasGamma = color.gamma != null || color.chromaticities != null
    return listOfNotNull(
      color.cicp?.let { PngPicture("$picture cICP", PngColor(cicp = it), null, picture.texel) },
      picture.profile?.let { PngPicture("$picture iCCP", PngColor(), it, picture.texel) },
      color.srgbIntent?.let { PngPicture("$picture sRGB", PngColor(srgbIntent = it), null, picture.texel) },
      if (hasGamma) PngPicture("$picture gAMA", gamma, null, picture.texel) else null,
    )
  }

  private fun srgbCodesOf(picture: PngPicture): List<Int> =
    pictureSrgbCodes(pictureColor(picture.pngOf(SIZE)), intArrayOf(picture.texel)).toList()

  private class Still(
    val picture: HeifPicture,
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
    ColorPictures.AVIFS.map { avif ->
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

  private fun heics(): List<HeicPicture> = ColorPictures.HEICS_WITH_SIGNAL_VARIANTS

  private fun cicpBytes(cicp: CicpTag): List<Int> =
    listOf(cicp.primaries, cicp.transfer, cicp.matrix, if (cicp.fullRange) 1 else 0)

  private companion object {
    val SIZE = Size(5, 3)

    // The signature and the IHDR chunk come first.
    const val COLOR_CHUNK_OFFSET = 33
    const val BIT_DEPTH = 8
    const val COLOR_TYPE = 9
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
