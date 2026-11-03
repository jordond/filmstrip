package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.evaluator
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.ycbcrReading
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
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.PASS_THROUGH
import dev.jordond.filmstrip.ffmpeg.internal.picture.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.picture.decodedPlanes
import dev.jordond.filmstrip.ffmpeg.internal.picture.otherCodecDepthReason
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.picture.planarYuv
import dev.jordond.filmstrip.ffmpeg.internal.picture.unknownDepthReason
import dev.jordond.filmstrip.ffmpeg.internal.picture.ycbcrScale
import dev.jordond.filmstrip.ffmpeg.internal.picture.yuv4mpegPlanes
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.AvifColorPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeicColorPictures
import dev.jordond.filmstrip.test.HeifStills
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
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
    val item = ColorPictures.NCLX_DISPLAY_P3.thumbnail()
    for (fullRange in listOf(true, false)) {
      val nclx = CicpTag(1, 13, YCGCO, fullRange)
      val bytes = HeifStills.still("avif", item.itemType, SHORT_AV1C, item.codestream, item.size, nclx = nclx)
      pictureYcbcrCoding(bytes).shouldNotBeNull().bitDepth.shouldBeNull()
      val read = decoder.decode(ImageSource.ofBytes(bytes))
      withClue("$nclx") {
        read.shouldBeInstanceOf<OverlayPictureDecode.Undrawn>()
      }.reason shouldBe unknownDepthReason(YCGCO)
    }
  }

  @Test
  fun `a matrix swscale cannot convert is not drawn on a codec whose depth core does not read`() {
    val other = YcbcrCoding(YCGCO, fullRange = true, CodecKind.Other, codestream = null)
    for (matrix in listOf(YCGCO, BT2020_CL)) {
      val route = pictureRoute(other.copy(matrix = matrix, primaries = BT2020, transfer = BT709_TRANSFER))
      withClue("$matrix") {
        route.shouldBeInstanceOf<PictureRoute.Refused>()
      }.reason shouldBe otherCodecDepthReason(matrix)
    }
    val avif = other.copy(codec = CodecKind.Av1)
    pictureRoute(avif).shouldBeInstanceOf<PictureRoute.Refused>().reason shouldBe unknownDepthReason(YCGCO)
  }

  @Test
  fun `a YCgCo or constant luminance picture is decoded from its planes at its own depth`() {
    for (bitDepth in listOf(8, 10, 12)) {
      for (fullRange in listOf(true, false)) {
        val range = if (fullRange) "pc" else "tv"
        for (codec in listOf(CodecKind.Av1, CodecKind.Hevc, CodecKind.Other)) {
          val coding = YcbcrCoding(YCGCO, fullRange, codec, codestream = null, bitDepth = bitDepth, chromaFormat = 1)
          val route =
            withClue("$codec $bitDepth $fullRange") {
              pictureRoute(coding).shouldBeInstanceOf<PictureRoute.Planes>()
            }
          val format = planarYuv(bitDepth)
          route.filter shouldBe "setparams=colorspace=bt709,scale=in_range=$range:out_range=$range,format=$format"
          route.bitDepth shouldBe bitDepth
        }
      }
    }
  }

  @Test
  fun `a YCgCo AVIF overlay on an SDR grade draws the color core reads`() {
    for (fullRange in listOf(true, false)) {
      val picture = if (fullRange) ColorPictures.NCLX_UNSPECIFIED else ColorPictures.NCLX_BT709_LIMITED
      val item = picture.thumbnail()
      val nclx = CicpTag(1, 13, YCGCO, fullRange)
      val bytes = HeifStills.still("avif", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
      val decoded = decoded(bytes)
      val stored = (decoded.samples as StoredSamples.Argb).argb[decoded.size.height / 2 * decoded.size.width]
      val spec = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)
      val resolution = BuiltInEffectResolver(decoder).resolve(spec, CAPABILITIES, SDR)
      val resolved =
        withClue("YCgCo full range $fullRange") {
          resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
        }
      val aux = resolved.effect.fragment.auxInputs
      val drawn = aux.first().image.shouldBeInstanceOf<ImageSource.Bytes>()
      val image = ImageIO.read(ByteArrayInputStream(drawn.bytes))
      val expected = pictureSrgbCodes(pictureColor(bytes), intArrayOf(stored)).single()
      withClue("YCgCo full range $fullRange") { image.getRGB(0, image.height / 2) shouldBe expected }
    }
  }

  @Test
  fun `every case the matrix test runs takes the route its table names and every route is read`() {
    val cases = MatrixCases.all
    val routes = MatrixRoute.entries.toSet()
    withClue("a route has no tag in the table") { ROUTES.values.toSet() shouldBe routes }
    withClue("the matrix test runs a case twice") { cases.toSet().size shouldBe cases.size }
    for (case in cases) {
      val route = withClue("$case has no route in the table") { ROUTES[case.tag].shouldNotBeNull() }
      for (still in case.stills()) {
        val coding = withClue(still.name) { pictureYcbcrCoding(still.bytes).shouldNotBeNull() }
        withClue(still.name) { coding.bitDepth shouldBe still.bitDepth }
        withClue(still.name) { coding.fullRange shouldBe case.fullRange }
        withClue(still.name) { coding.matrix shouldBe case.tag.matrix }
        withClue(still.name) { matrixRoute(coding) shouldBe route }
      }
    }
    val read = cases.groupBy({ it.container to it.fullRange }, { it.tag })
    val avifFull = read[MatrixContainer.Avif to true].orEmpty()
    withClue("the table and the AVIFs read at full range name different tags") { avifFull.toSet() shouldBe ROUTES.keys }
    val heicFull = read[MatrixContainer.Heic to true].orEmpty()
    val heicLimited = read[MatrixContainer.Heic to false].orEmpty()
    withClue("a HEIC is read at one range") { heicLimited.toSet() shouldBe heicFull.toSet() }
    withClue("the HEICs do not read one tag for each route") { heicFull.size shouldBe routes.size }
    withClue("a route is not read from a HEIC") { heicFull.map(ROUTES::getValue).toSet() shouldBe routes }
    val avifLimited = read[MatrixContainer.Avif to false].orEmpty()
    withClue("a route is not read from an AVIF at limited range") {
      avifLimited.map(ROUTES::getValue).toSet() shouldBe routes
    }
    val planes = ROUTES.filter { it.value != MatrixRoute.Swscale }.map { it.key.matrix }
    val limited = avifLimited.map { it.matrix }
    withClue("a matrix read from its planes is not read from an AVIF at limited range") {
      limited.containsAll(planes).shouldBeTrue()
    }
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
    val reason = withClue("core reads $nclx") { reading.shouldBeInstanceOf<YcbcrReading.Refused>() }.reason
    val avif = ColorPictures.NCLX_UNSPECIFIED.thumbnail()
    val heic = ColorPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    val pictures =
      listOf(
        "AVIF" to HeifStills.still("avif", avif.itemType, avif.configuration, avif.codestream, avif.size, nclx = nclx),
        "twelve-bit AVIF" to AvifColorPictures.twelveBit(nclx).bytes(),
        "HEIC" to HeifStills.still("heic", heic.itemType, heic.configuration, heic.codestream, heic.size, nclx = nclx),
        "twelve-bit HEIC" to HeicColorPictures.twelveBit(nclx),
      )
    for ((name, bytes) in pictures) {
      val read = decoder.decode(ImageSource.ofBytes(bytes))
      withClue("$name $nclx") { read.shouldBeInstanceOf<OverlayPictureDecode.Undrawn>() }.reason shouldBe reason
    }
    val spec = ImageOverlay(ImageSource.ofBytes(pictures.first().second), Corner.TopStart)
    val resolution = BuiltInEffectResolver(decoder).resolve(spec, CAPABILITIES, SDR)
    val message = withClue("$nclx") { resolution.shouldBeInstanceOf<EffectResolution.Unsupported>() }.message
    withClue("$nclx said $message") { (reason in message).shouldBeTrue() }
    withClue("$nclx said $message") { (UNDECODED_ADVICE in message).shouldBeFalse() }
  }

  // The route a picture whose coding is [coding] lands through, or null when it is refused, read as identity, or handed
  // to ffmpeg untouched because its color is unknown, none of which the matrix test reads.
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
        val swscale = withClue("$name read no PNG") { png.shouldNotBeNull() }
        val range = if (fullRange) "pc" else "tv"
        val hop = "scale=in_range=$range:out_range=$range,format=${planarYuv(bitDepth)}"
        val stream = ffmpeg(pattern, hop, "-f", "yuv4mpegpipe", "-strict", "-1", "-").inputStream()
        val planes = withClue("$name read no planes") { yuv4mpegPlanes(stream, bitDepth).shouldNotBeNull() }
        val core = decodedPlanes(planes, coding.reading.evaluator(bitDepth, fullRange))
        val swscaleCodes = codes(swscale)
        val coreCodes = codes(core)
        withClue(name) { swscaleCodes.size shouldBe coreCodes.size }
        val worst = coreCodes.indices.maxOf { abs(coreCodes[it] - swscaleCodes[it]) }
        withClue("$name sits $worst codes from core") { (worst <= PICTURE_DRIFT).shouldBeTrue() }
        val colors = coreCodes.chunked(3).distinct().size
        withClue("$name has $colors colors, too few to tell matrices apart") {
          (colors > PATTERN_SPREAD).shouldBeTrue()
        }
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
    withClue("ffmpeg failed on $filter") { process.waitFor() shouldBe 0 }
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
    return withClue("this ffmpeg decoded nothing") { (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull() }
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

    // Fewer distinct colors than this and the pattern is too flat to tell one matrix from another.
    const val PATTERN_SPREAD = 256

    // The advice a picture this ffmpeg could not decode carries, which a picture it does not draw must not.
    const val UNDECODED_ADVICE = "Check that this ffmpeg build decodes AVIF and HEIC."

    // An av1C cut off after its marker and profile byte, so the byte holding the depth is missing.
    val SHORT_AV1C = hex("0000000a" + "61763143" + "8120")

    fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
