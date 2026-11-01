package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.effects.overlay.decodedPng
import dev.jordond.filmstrip.ffmpeg.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.PASS_THROUGH
import dev.jordond.filmstrip.ffmpeg.internal.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.decodedPlanes
import dev.jordond.filmstrip.ffmpeg.internal.otherCodecDepthReason
import dev.jordond.filmstrip.ffmpeg.internal.pictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.planarYuv
import dev.jordond.filmstrip.ffmpeg.internal.unknownDepthReason
import dev.jordond.filmstrip.ffmpeg.internal.ycbcrScale
import dev.jordond.filmstrip.ffmpeg.internal.yuv4mpegPlanes
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.evaluator
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.ycbcrReading
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicColourPictures
import dev.jordond.filmstrip.test.HeifStills
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The matrices this ffmpeg does not land through swscale, and swscale's own reading of the ones it does.
 *
 * A matrix core refuses is not drawn with core's reason before ffmpeg runs, one tag a test, so these need no HEIF
 * demuxer. The overlay that carries one is refused with that reason and none of the advice for a picture ffmpeg could
 * not decode. A matrix swscale cannot convert is not drawn without a depth to read its planes at. Every matrix swscale
 * names is held, texel for texel over a test pattern, against core's evaluator on the same planes. Every case in
 * [MatrixCases.all], the list [FfmpegPictureMatrixTest] runs, is read by core at its own matrix, range and depth and
 * takes the route a table names for its tag. The list reads every tag in the table from an AVIF at full range, one tag
 * for each route from a HEIC at both ranges, and from an AVIF at limited range one tag for each route and every matrix
 * read from its planes.
 */
class FfmpegPictureMatrixRouteTest {
  @Test
  fun `a picture tagged 11 YDzDx is not drawn with core's reason`() = assertUndrawn(CicpTag(1, 13, 11, false))

  @Test
  fun `a picture tagged 14 ICtCp is not drawn with core's reason`() = assertUndrawn(CicpTag(9, 16, 14, false))

  @Test
  fun `a picture tagged 3 reserved is not drawn with core's reason`() = assertUndrawn(CicpTag(1, 13, 3, true))

  @Test
  fun `a picture tagged 15 past ICtCp is not drawn with core's reason`() = assertUndrawn(CicpTag(1, 13, 15, true))

  @Test
  fun `a picture tagged 10 beside PQ is not drawn with core's reason`() = assertUndrawn(CicpTag(9, 16, 10, false))

  @Test
  fun `a picture tagged 13 beside HLG is not drawn with core's reason`() = assertUndrawn(CicpTag(9, 18, 13, true))

  @Test
  fun `a picture tagged 12 on unknown primaries is not drawn with core's reason`() =
    assertUndrawn(CicpTag(2, 13, 12, true))

  @Test
  fun `a picture tagged 13 on unknown primaries is not drawn with core's reason`() =
    assertUndrawn(CicpTag(2, 13, 13, false))

  @Test
  fun `a YCgCo AVIF whose av1C does not say its depth is not drawn`() {
    val item = ColourPictures.NCLX_DISPLAY_P3.thumbnail()
    for (fullRange in listOf(true, false)) {
      val nclx = CicpTag(1, 13, YCGCO, fullRange)
      val bytes = HeifStills.still("avif", item.itemType, SHORT_AV1C, item.codestream, item.size, nclx = nclx)
      assertNull(assertNotNull(pictureYcbcrCoding(bytes)).bitDepth)
      val read = decoder.decode(ImageSource.ofBytes(bytes))
      assertEquals(unknownDepthReason(YCGCO), assertIs<OverlayPictureDecode.Undrawn>(read, "$nclx").reason)
    }
  }

  @Test
  fun `a matrix swscale cannot convert is not drawn on a codec whose depth core does not read`() {
    val other = YcbcrCoding(YCGCO, fullRange = true, CodecKind.Other, codestream = null)
    for (matrix in listOf(YCGCO, BT2020_CL)) {
      val route = pictureRoute(other.copy(matrix = matrix, primaries = BT2020, transfer = BT709_TRANSFER))
      assertEquals(otherCodecDepthReason(matrix), assertIs<PictureRoute.Refused>(route, "$matrix").reason)
    }
    val avif = other.copy(codec = CodecKind.Av1)
    assertEquals(unknownDepthReason(YCGCO), assertIs<PictureRoute.Refused>(pictureRoute(avif)).reason)
  }

  @Test
  fun `a YCgCo or constant luminance picture is decoded from its planes at its own depth`() {
    for (bitDepth in listOf(8, 10, 12)) {
      for (fullRange in listOf(true, false)) {
        val range = if (fullRange) "pc" else "tv"
        for (codec in listOf(CodecKind.Av1, CodecKind.Hevc, CodecKind.Other)) {
          val coding = YcbcrCoding(YCGCO, fullRange, codec, codestream = null, bitDepth = bitDepth, chromaFormat = 1)
          val route = assertIs<PictureRoute.Planes>(pictureRoute(coding), "$codec $bitDepth $fullRange")
          val format = planarYuv(bitDepth)
          assertEquals("setparams=colorspace=bt709,scale=in_range=$range:out_range=$range,format=$format", route.filter)
          assertEquals(bitDepth, route.bitDepth)
        }
      }
    }
  }

  @Test
  fun `a YCgCo AVIF overlay on an SDR grade draws the colour core reads`() {
    for (fullRange in listOf(true, false)) {
      val picture = if (fullRange) ColourPictures.NCLX_UNSPECIFIED else ColourPictures.NCLX_BT709_LIMITED
      val item = picture.thumbnail()
      val nclx = CicpTag(1, 13, YCGCO, fullRange)
      val bytes = HeifStills.still("avif", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
      val decoded = decoded(bytes)
      val stored = (decoded.samples as StoredSamples.Argb).argb[decoded.size.height / 2 * decoded.size.width]
      val spec = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)
      val resolution = BuiltInEffectResolver(decoder).resolve(spec, CAPABILITIES, SDR)
      val resolved = assertIs<EffectResolution.Resolved>(resolution, "YCgCo full range $fullRange")
      val aux = resolved.effect.fragment.auxInputs
      val drawn = assertIs<ImageSource.Bytes>(aux.first().image)
      val image = ImageIO.read(ByteArrayInputStream(drawn.bytes))
      val expected = pictureSrgbCodes(pictureColour(bytes), intArrayOf(stored)).single()
      assertEquals(expected, image.getRGB(0, image.height / 2), "YCgCo full range $fullRange")
    }
  }

  @Test
  fun `every case the matrix test runs takes the route its table names and every route is read`() {
    val cases = MatrixCases.all
    val routes = MatrixRoute.entries.toSet()
    assertEquals(routes, ROUTES.values.toSet(), "a route has no tag in the table")
    assertEquals(cases.size, cases.toSet().size, "the matrix test runs a case twice")
    for (case in cases) {
      val route = assertNotNull(ROUTES[case.tag], "$case has no route in the table")
      for (still in case.stills()) {
        val coding = assertNotNull(pictureYcbcrCoding(still.bytes), still.name)
        assertEquals(still.bitDepth, coding.bitDepth, still.name)
        assertEquals(case.fullRange, coding.fullRange, still.name)
        assertEquals(case.tag.matrix, coding.matrix, still.name)
        assertEquals(route, matrixRoute(coding), still.name)
      }
    }
    val read = cases.groupBy({ it.container to it.fullRange }, { it.tag })
    val avifFull = read[MatrixContainer.Avif to true].orEmpty()
    assertEquals(ROUTES.keys, avifFull.toSet(), "the table and the AVIFs read at full range name different tags")
    val heicFull = read[MatrixContainer.Heic to true].orEmpty()
    val heicLimited = read[MatrixContainer.Heic to false].orEmpty()
    assertEquals(heicFull.toSet(), heicLimited.toSet(), "a HEIC is read at one range")
    assertEquals(routes.size, heicFull.size, "the HEICs do not read one tag for each route")
    assertEquals(routes, heicFull.map(ROUTES::getValue).toSet(), "a route is not read from a HEIC")
    val avifLimited = read[MatrixContainer.Avif to false].orEmpty()
    assertEquals(routes, avifLimited.map(ROUTES::getValue).toSet(), "a route is not read from an AVIF at limited range")
    val planes = ROUTES.filter { it.value != MatrixRoute.Swscale }.map { it.key.matrix }
    val limited = avifLimited.map { it.matrix }
    assertTrue(limited.containsAll(planes), "a matrix read from its planes is not read from an AVIF at limited range")
  }

  @Test
  fun `swscale reads 1 BT709 as core does`() = assertSwscaleAgrees(1)

  @Test
  fun `swscale reads 4 FCC as core does`() = assertSwscaleAgrees(4)

  @Test
  fun `swscale reads 5 BT470BG as core does`() = assertSwscaleAgrees(5)

  @Test
  fun `swscale reads 6 BT601 as core does`() = assertSwscaleAgrees(6)

  @Test
  fun `swscale reads 7 SMPTE 240M as core does`() = assertSwscaleAgrees(7)

  @Test
  fun `swscale reads 9 BT2020 as core does`() = assertSwscaleAgrees(9)

  // [nclx] on the eight- and twelve-bit AVIF and HEIC, each not drawn with the reason core gives its reading, and the
  // overlay carrying the eight-bit AVIF refused with that reason and without the advice for an undecoded picture.
  private fun assertUndrawn(nclx: CicpTag) {
    val reading = ycbcrReading(nclx.matrix, nclx.primaries, nclx.transfer)
    val reason = assertIs<YcbcrReading.Refused>(reading, "core reads $nclx").reason
    val avif = ColourPictures.NCLX_UNSPECIFIED.thumbnail()
    val heic = ColourPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    val pictures =
      listOf(
        "AVIF" to HeifStills.still("avif", avif.itemType, avif.configuration, avif.codestream, avif.size, nclx = nclx),
        "twelve-bit AVIF" to AvifColourPictures.twelveBit(nclx).bytes(),
        "HEIC" to HeifStills.still("heic", heic.itemType, heic.configuration, heic.codestream, heic.size, nclx = nclx),
        "twelve-bit HEIC" to HeicColourPictures.twelveBit(nclx),
      )
    for ((name, bytes) in pictures) {
      val read = decoder.decode(ImageSource.ofBytes(bytes))
      assertEquals(reason, assertIs<OverlayPictureDecode.Undrawn>(read, "$name $nclx").reason)
    }
    val spec = ImageOverlay(ImageSource.ofBytes(pictures.first().second), Corner.TopStart)
    val resolution = BuiltInEffectResolver(decoder).resolve(spec, CAPABILITIES, SDR)
    val message = assertIs<EffectResolution.Unsupported>(resolution, "$nclx").message
    assertTrue(reason in message, "$nclx said $message")
    assertFalse(UNDECODED_ADVICE in message, "$nclx said $message")
  }

  // The route a picture whose coding is [coding] lands through, or null when it is refused, read as identity, or handed
  // to ffmpeg untouched because its colour is unknown, none of which the matrix test reads.
  private fun matrixRoute(coding: YcbcrCoding): MatrixRoute? =
    when (val route = pictureRoute(coding)) {
      is PictureRoute.Converted -> {
        MatrixRoute.Swscale.takeIf { route.filter != PASS_THROUGH }
      }
      is PictureRoute.Refused -> {
        null
      }
      is PictureRoute.Planes -> {
        when (coding.reading) {
          is YcbcrReading.KrKb -> MatrixRoute.DerivedPlanes
          YcbcrReading.YCgCo -> MatrixRoute.YCgCoPlanes
          is YcbcrReading.ConstantLuminance -> MatrixRoute.ConstantLuminancePlanes
          YcbcrReading.Identity, is YcbcrReading.Refused -> null
        }
      }
    }

  // A test pattern at eight bits 4:4:4 and twelve bits 4:2:0, at both ranges, converted by swscale through
  // [ycbcrScale] for [matrix] and by core's evaluator from the planes the same hop hands back, every texel within
  // PICTURE_DRIFT.
  private fun assertSwscaleAgrees(matrix: Int) {
    for ((bitDepth, source) in listOf(EIGHT_BITS to "yuv444p", TWELVE_BITS to "yuv420p12le")) {
      for (fullRange in listOf(true, false)) {
        val name = "matrix $matrix at $bitDepth bits, full range $fullRange"
        val chroma = if (bitDepth == EIGHT_BITS) 3 else 1
        val coding = YcbcrCoding(matrix, fullRange, CodecKind.Av1, null, bitDepth = bitDepth, chromaFormat = chroma)
        val pattern = "testsrc2=s=${PATTERN.width}x${PATTERN.height}:d=1,format=$source"
        val png = decodedPng(ffmpeg(pattern, ycbcrScale(coding), "-c:v", "png", "-f", "image2pipe", "-"))
        val swscale = assertNotNull(png, "$name read no PNG")
        val range = if (fullRange) "pc" else "tv"
        val hop = "scale=in_range=$range:out_range=$range,format=${planarYuv(bitDepth)}"
        val stream = ffmpeg(pattern, hop, "-f", "yuv4mpegpipe", "-strict", "-1", "-").inputStream()
        val planes = assertNotNull(yuv4mpegPlanes(stream, bitDepth), "$name read no planes")
        val core = decodedPlanes(planes, coding.reading.evaluator(bitDepth, fullRange))
        val swscaleCodes = codes(swscale)
        val coreCodes = codes(core)
        assertEquals(coreCodes.size, swscaleCodes.size, name)
        val worst = coreCodes.indices.maxOf { abs(coreCodes[it] - swscaleCodes[it]) }
        assertTrue(worst <= PICTURE_DRIFT, "$name sits $worst codes from core")
        val colours = coreCodes.chunked(3).distinct().size
        assertTrue(colours > PATTERN_SPREAD, "$name has $colours colours, too few to tell matrices apart")
      }
    }
  }

  // The first frame of [pattern] through [filter] into [output], as ffmpeg writes it to stdout.
  private fun ffmpeg(
    pattern: String,
    filter: String,
    vararg output: String,
  ): ByteArray {
    val command =
      listOf("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", pattern) +
        listOf("-frames:v", "1", "-vf", filter) + output
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val bytes = process.inputStream.readAllBytes()
    assertEquals(0, process.waitFor(), "ffmpeg failed on $filter")
    return bytes
  }

  // Every red, green and blue of [picture] in eight-bit codes, texel by texel.
  private fun codes(picture: DecodedPicture): List<Int> =
    when (val samples = picture.samples) {
      is StoredSamples.Argb -> {
        samples.argb.flatMap { argb -> listOf(argb shr 16, argb shr 8, argb).map { it and 0xFF } }
      }
      is StoredSamples.Rgba16 -> {
        samples.rgba.indices
          .filter { it % 4 != 3 }
          .map { ((samples.rgba[it].toInt() and SIXTEEN_BIT_MAX) / WIDE_PER_CODE).roundToInt() }
      }
    }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing")
  }

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")

    // How a picture the matrix test reads lands: through swscale, or from its planes through core's evaluator for a
    // matrix derived from its primaries, for YCgCo, or for constant luminance.
    enum class MatrixRoute { Swscale, DerivedPlanes, YCgCoPlanes, ConstantLuminancePlanes }

    // The route each tag the matrix test reads lands through, at either range and either depth.
    val ROUTES =
      mapOf(
        MatrixTags.BT709 to MatrixRoute.Swscale,
        MatrixTags.UNSPECIFIED to MatrixRoute.Swscale,
        MatrixTags.FCC to MatrixRoute.Swscale,
        MatrixTags.BT470BG to MatrixRoute.Swscale,
        MatrixTags.BT601 to MatrixRoute.Swscale,
        MatrixTags.SMPTE_240M to MatrixRoute.Swscale,
        MatrixTags.YCGCO to MatrixRoute.YCgCoPlanes,
        MatrixTags.BT2020 to MatrixRoute.Swscale,
        MatrixTags.BT2020_CL to MatrixRoute.ConstantLuminancePlanes,
        MatrixTags.DERIVED_ON_BT709 to MatrixRoute.Swscale,
        MatrixTags.DERIVED_ON_BT2020 to MatrixRoute.Swscale,
        MatrixTags.DERIVED_ON_P3 to MatrixRoute.DerivedPlanes,
        MatrixTags.DERIVED_CL_ON_BT2020 to MatrixRoute.ConstantLuminancePlanes,
        MatrixTags.DERIVED_CL_ON_P3 to MatrixRoute.ConstantLuminancePlanes,
      )
    val FRAME = Size(640, 360)
    val PATTERN = Size(256, 192)
    val SDR =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )

    const val YCGCO = 8
    const val BT2020_CL = 10
    const val BT2020 = 9
    const val BT709_TRANSFER = 1
    const val EIGHT_BITS = 8
    const val TWELVE_BITS = 12
    const val SIXTEEN_BIT_MAX = 0xFFFF

    // An eight-bit code at sixteen bits.
    const val WIDE_PER_CODE = 257.0

    // Fewer distinct colours than this and the pattern is too flat to tell one matrix from another.
    const val PATTERN_SPREAD = 256

    // The advice a picture this ffmpeg could not decode carries, which a picture it does not draw must not.
    const val UNDECODED_ADVICE = "Check that this ffmpeg build decodes AVIF and HEIC."

    // An av1C cut off after its marker and profile byte, so the byte holding the depth is missing.
    val SHORT_AV1C = hex("0000000a" + "61763143" + "8120")

    fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
