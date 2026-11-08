package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.color.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.ColorLanding
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.HdrRender
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.assertSdrLandings
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HevcVui
import dev.jordond.filmstrip.test.ycbcrToRgb
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * AVIF and HEIC overlays, whose samples this ffmpeg turns from YCbCr into RGB with the matrix and range their `nclx`
 * box records.
 *
 * ffmpeg lets the codestream's color description win over the box, so the decoder converts an AVIF with the
 * box itself and hands ffmpeg a HEIC whose codestream core rewrote to state the box. Each [ColorPictures] still is
 * decoded and its stored texel held against the ITU-T H.273 conversion of its codes, then resolved on SDR, PQ and HLG
 * grades against [pictureSrgbCodes] and [pictureLight] of that texel, and exported on SDR so the graph's own render is
 * read. HEICs whose codestream disagrees with their box, and tiled AVIFs and HEICs, prove the box wins over the
 * codestream, and HEICs with no box prove the codestream is read when it is all there is. ffmpeg's limits are pinned by
 * name: a conversion to sixteen-bit RGB tops out at 255/256 at limited range and 1023/1024 at full.
 */
class FfmpegPictureYcbcrTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `every AVIF still decodes with its nclx matrix and range and lands where core puts it`() {
    if (!fixture.available()) return
    for (picture in AVIF_STILLS) assertStill(picture.name, picture.bytes(), picture.nclx, picture.ycbcr)
  }

  @Test
  fun `every HEIC still decodes with its nclx matrix and range and lands where core puts it`() {
    if (!fixture.available()) return
    for (picture in HEIC_STILLS) {
      val vui = picture.vui
      assertStill(picture.name, picture.bytes(), picture.nclx, picture.ycbcr, vui.color?.matrix, vui.fullRange)
    }
  }

  // ffmpeg takes the matrix and range from the codestream's VUI, so the codestream core rewrites to state the box is
  // what lands each of these where the box says.
  @Test
  fun `a HEIC whose codestream disagrees with its nclx decodes with the box, tiled or not`() {
    if (!fixture.available()) return
    val disagreeing = ColorPictures.HEIC_SIGNAL_VARIANTS.filter { it.nclx != null }
    withClue("no HEIC carries both an nclx and a codestream of its own") { disagreeing.isNotEmpty().shouldBeTrue() }
    for (picture in disagreeing) {
      val nclx = checkNotNull(picture.nclx)
      val (y, cb, cr) = picture.ycbcr
      val signal = picture.vui.color
      val vui =
        ycbcrToRgb(
          y,
          cb,
          cr,
          signal?.matrix ?: UNSPECIFIED,
          signal?.primaries ?: UNSPECIFIED,
          signal?.transfer ?: UNSPECIFIED,
          picture.vui.fullRange,
        )
      val box = ycbcrToRgb(y, cb, cr, nclx.matrix, nclx.primaries, nclx.transfer, nclx.fullRange)
      // A codestream that differs from the nclx only in its primaries reads alike here.
      val primariesOnly = picture.vui.color?.let { it.matrix == nclx.matrix && it.fullRange == nclx.fullRange } == true
      val apart = vui.indices.any { abs(vui[it] - box[it]) > PICTURE_DRIFT }
      withClue("${picture.name} reads alike either way") { (apart || primariesOnly).shouldBeTrue() }
      assertStill(picture.name, picture.bytes(), nclx, picture.ycbcr)
    }
    for (picture in disagreeing) assertStill("tiled ${picture.name}", picture.gridBytes(), picture.nclx, picture.ycbcr)
  }

  // Every decoder reads a HEIC's VUI when it has no nclx, so its codestream alone says where it lands.
  @Test
  fun `a HEIC with no nclx decodes with the matrix and range its codestream signals, tiled or not`() {
    if (!fixture.available()) return
    val signalling = listOf(ColorPictures.HEIC_NCLX_LIMITED_VUI_FULL, ColorPictures.HEIC_NCLX_BT601_VUI_BT709)
    val vuis = signalling.map { it.vui }
    vuis shouldBe listOf(HevcVui.FullRange, HevcVui.FullRangeBt709)
    for (picture in signalling) {
      val item = picture.thumbnail()
      val flat = HeifStills.still("heic", item.itemType, item.configuration, item.codestream, item.size)
      val vui = picture.vui
      assertStill("no nclx $vui", flat, null, picture.ycbcr, vui.color?.matrix, vui.fullRange)
    }
    for (picture in signalling) {
      val item = picture.thumbnail()
      val tiled = HeifStills.grid("heic", item.itemType, item.configuration, item.codestream, item.size)
      val vui = picture.vui
      assertStill("tiled no nclx $vui", tiled, null, picture.ycbcr, vui.color?.matrix, vui.fullRange)
    }
  }

  // Android's HEIC decoder reads a codestream's matrix only beside primaries it pairs it with, so the rewritten
  // codestream is held to land the BT.709 matrix beside Display P3's primaries 12 here too.
  @Test
  fun `a HEIC with the BT709 matrix beside primaries 12 decodes with its nclx, tiled or not`() {
    if (!fixture.available()) return
    val item = ColorPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    val ycbcr = ColorPictures.HEIC_NCLX_BT709_FULL.ycbcr
    val tags = listOf(true, false).map { CicpTag(DISPLAY_P3_PRIMARIES, SRGB_TRANSFER, BT709, it) }
    for (nclx in tags) {
      val flat = HeifStills.still("heic", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
      assertStill("primaries 12 $nclx", flat, nclx, ycbcr)
    }
    for (nclx in tags) {
      val tiled = HeifStills.grid("heic", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
      assertStill("tiled primaries 12 $nclx", tiled, nclx, ycbcr)
    }
  }

  // A grid's nclx sits on the grid item, which the decode reads beside the tiles it assembles.
  @Test
  fun `a tiled AVIF and HEIC decode and measure at the grid's size with the grid's nclx`() {
    if (!fixture.available()) return
    val avif = ColorPictures.NCLX_BT709_LIMITED
    val grids = mutableListOf(Grid(avif.name, avif.gridBytes(), AVIF_GRID, avif.nclx, avif.ycbcr))
    val heic = ColorPictures.HEIC_NCLX_BT709_LIMITED
    grids += Grid(heic.name, heic.gridBytes(), HEIC_GRID, heic.nclx, heic.ycbcr)
    for (grid in grids) {
      withClue("tiled ${grid.name} decoded at a tile's size") { decoded(grid.bytes).size shouldBe grid.size }
      val measured = decoder.measure(ImageSource.ofBytes(grid.bytes))
      withClue("tiled ${grid.name} measured") { (measured as? OverlayPictureRead.Read)?.value shouldBe grid.size }
      assertStill("tiled ${grid.name}", grid.bytes, grid.nclx, grid.ycbcr)
    }
  }

  @Test
  fun `every AVIF and HEIC still lands on an SDR export where core's codes put it`() =
    runTest(timeout = ExportConstants.TIMEOUT) {
      if (!fixture.available()) return@runTest
      val heics = HEIC_STILLS
      val stills = AVIF_STILLS.map { it.name to it.bytes() } + heics.map { it.name to it.bytes() }
      val landings =
        stills.map { (name, bytes) ->
          val texel = intArrayOf(centreOf(decoded(bytes)))
          val color = pictureColor(bytes)
          ColorLanding(name, bytes, pictureSrgbCodes(color, texel).single(), pictureLight(color, texel))
        }
      fixture.assertSdrLandings(landings, "filmstrip-ycbcr")
    }

  /**
   * ffmpeg's own limit: a ten-bit picture converts to sixteen-bit RGB at 255/256 of full scale.
   *
   * Limited mid grey 502 reads 32641 where 32768 is exact, and limited white 940 reads 65283. No flag or format hop
   * moves it, so a picture deeper than eight bits lands that much low and nothing here rescales it. A platform fix
   * flips this test.
   */
  @Test
  fun `a ten bit picture decodes to sixteen bits at ffmpeg's 255 over 256 of full scale`() {
    if (!fixture.available()) return
    for ((luma, read) in listOf(MID_LUMA to MID_READ, WHITE_LUMA to WHITE_READ)) {
      val samples = decodedY4m(luma, tenBit = true).samples.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
      val grey = (0 until 3).map { samples[it].toInt() and SIXTEEN_BIT_MAX }
      withClue("ten-bit luma $luma read $grey, not ffmpeg's $read") { grey.all { abs(it - read) <= 1 }.shouldBeTrue() }
    }
  }

  /**
   * ffmpeg's own limit at full range: ten-bit white converts to sixteen-bit RGB at 1023/1024 of full scale.
   *
   * Full range white 1023 reads 65472 where 65535 is exact, ten bits shifted up by six. A platform fix flips this test.
   */
  @Test
  fun `a full range ten bit picture decodes to sixteen bits at ffmpeg's 1023 over 1024 of full scale`() {
    if (!fixture.available()) return
    val decoded = decodedY4m(FULL_WHITE_LUMA, tenBit = true, fullRange = true)
    val samples = decoded.samples.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
    val white = (0 until 3).map { samples[it].toInt() and SIXTEEN_BIT_MAX }
    withClue("full range white read $white, not $FULL_WHITE_READ") {
      white.all { abs(it - FULL_WHITE_READ) <= 1 }.shouldBeTrue()
    }
    withClue("full range white read at full scale") { white.all { it < SIXTEEN_BIT_MAX }.shouldBeTrue() }
  }

  /**
   * ffmpeg's limit carried onto a grade: a ten-bit picture lands from the sixteen-bit samples ffmpeg hands back.
   *
   * Limited mid grey lands where [pictureLight] puts ffmpeg's 32641, which is short of where the exact 32768 would land
   * it. A platform fix flips the second check.
   */
  @Test
  fun `a ten bit picture lands on PQ and HLG from ffmpeg's samples short of full scale`() {
    if (!fixture.available()) return
    withY4m(MID_LUMA, tenBit = true) { picture ->
      val decoded =
        withClue("decoded nothing") {
          (decoder.decode(picture) as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
        }
      val samples = decoded.samples.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
      val texel = samples.copyOf(4)
      val grey = (0 until 3).map { texel[it].toInt() and SIXTEEN_BIT_MAX }
      withClue("ten-bit mid grey read $grey, not ffmpeg's $MID_READ") {
        grey.all { abs(it - MID_READ) <= 1 }.shouldBeTrue()
      }
      val exact = ShortArray(4) { if (it == 3) SIXTEEN_BIT_MAX.toShort() else EXACT_MID.toShort() }
      val spec = ImageOverlay(picture, Corner.TopStart)
      for (transfer in HdrTransfer.entries) {
        val mapped =
          resolvedImage(
            spec,
            HdrRender.attributes(transfer, 2.seconds),
          ).shouldBeInstanceOf<ImageSource.Bytes>().bytes
        val graded = ImageIO.read(ByteArrayInputStream(mapped))
        val landed =
          graded.raster
            .getPixel(graded.width / 2, graded.height / 2, IntArray(4))
            .copyOf(3)
            .toList()
        withClue("$transfer ten-bit mid grey") { landed shouldBe wideCodes(transfer, pictureLight(null, texel)) }
        withClue("$transfer landed at the exact grey") {
          (wideCodes(transfer, pictureLight(null, exact)) != landed).shouldBeTrue()
        }
      }
    }
  }

  // The picture path keeps an eight-bit picture at eight bits, which ffmpeg converts exactly.
  @Test
  fun `an eight bit picture decodes to eight bits without ffmpeg's sixteen bit scale`() {
    if (!fixture.available()) return
    val argb = decodedY4m(EIGHT_BIT_WHITE, tenBit = false).samples.shouldBeInstanceOf<StoredSamples.Argb>().argb
    withClue("eight-bit limited white") { argb.first() shouldBe 0xFFFFFFFF.toInt() }
  }

  // [bytes] decoded and held at its middle against the H.273 conversion of [ycbcr] with [nclx]'s matrix and range,
  // then resolved on every grade against core's functions of the texel this ffmpeg decoded.
  // Without an nclx the decoder's own reading of the codestream stands, [codestreamMatrix] and [codestreamFullRange],
  // which for an AVIF here is its sequence header's full range and for a HEIC its VUI.
  private fun assertStill(
    name: String,
    bytes: ByteArray,
    nclx: CicpTag?,
    ycbcr: List<Int>,
    codestreamMatrix: Int? = null,
    codestreamFullRange: Boolean = true,
  ) {
    val decoded = decoded(bytes)
    val stored = centreOf(decoded)
    val matrix = nclx?.matrix ?: codestreamMatrix ?: UNSPECIFIED
    val coding = pictureYcbcrCoding(bytes)
    val primaries = coding?.primaries ?: UNSPECIFIED
    val transfer = coding?.transfer ?: UNSPECIFIED
    val (y, cb, cr) = ycbcr
    val expected = ycbcrToRgb(y, cb, cr, matrix, primaries, transfer, nclx?.fullRange ?: codestreamFullRange).toList()
    val read = listOf(stored shr 16, stored shr 8, stored).map { it and 0xFF }
    val near = read.indices.all { abs(read[it] - expected[it]) <= PICTURE_DRIFT }
    withClue("$name decoded $read, not $expected") { near.shouldBeTrue() }

    val color = pictureColor(bytes)
    val spec = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)
    val drawn =
      withClue("$name reached the graph undecoded") {
        resolvedImage(spec, SDR).shouldBeInstanceOf<ImageSource.Bytes>()
      }
    val srgb = pictureSrgbCodes(color, intArrayOf(stored)).single()
    val image = ImageIO.read(ByteArrayInputStream(drawn.bytes))
    withClue("$name on an SDR grade") { image.getRGB(image.width / 2, image.height / 2) shouldBe srgb }

    for (transfer in HdrTransfer.entries) {
      val mapped =
        resolvedImage(
          spec,
          HdrRender.attributes(transfer, 2.seconds),
        ).shouldBeInstanceOf<ImageSource.Bytes>().bytes
      val graded = ImageIO.read(ByteArrayInputStream(mapped))
      val signal = transfer.signalFromNits(pictureLight(color, intArrayOf(stored)).copyOf(3))
      val wide = signal.map { (it.coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt() }
      val texel =
        graded.raster
          .getPixel(graded.width / 2, graded.height / 2, IntArray(4))
          .copyOf(3)
          .toList()
      withClue("$transfer $name") { texel shouldBe wide }
    }
  }

  // The sixteen-bit codes a grade's raster holds for the opaque light in the first three of [light].
  private fun wideCodes(
    transfer: HdrTransfer,
    light: FloatArray,
  ): List<Int> = transfer.signalFromNits(light.copyOf(3)).map { (it.coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt() }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return withClue("this ffmpeg decoded nothing: $read") {
      (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
    }
  }

  private fun centreOf(decoded: DecodedPicture): Int {
    val argb =
      withClue("an eight-bit still decoded deeper") {
        decoded.samples.shouldBeInstanceOf<StoredSamples.Argb>()
      }.argb
    return argb[decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2]
  }

  // A flat grey 16 by 16 YUV 4:2:0 picture of [luma], limited range unless [fullRange], as a Y4M file, decoded by this
  // ffmpeg.
  private fun decodedY4m(
    luma: Int,
    tenBit: Boolean,
    fullRange: Boolean = false,
  ): DecodedPicture =
    withY4m(luma, tenBit, fullRange) { picture ->
      val read = decoder.decode(picture)
      withClue("this ffmpeg decoded nothing: $read") { (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull() }
    }

  // A flat grey 16 by 16 YUV 4:2:0 picture of [luma], limited range unless [fullRange], as a Y4M file that lives for
  // the length of [use].
  private fun <T> withY4m(
    luma: Int,
    tenBit: Boolean,
    fullRange: Boolean = false,
    use: (ImageSource) -> T,
  ): T {
    val side = 16
    val chroma = if (tenBit) TEN_BIT_MID_CHROMA else EIGHT_BIT_MID_CHROMA
    val range = if (fullRange) " XCOLORRANGE=FULL" else ""
    val header = "YUV4MPEG2 W$side H$side F25:1 Ip A1:1 C420${if (tenBit) "p10" else ""}$range\nFRAME\n"
    val planes = List(side * side) { luma } + List(side * side / 2) { chroma }
    val body = planes.flatMap { if (tenBit) listOf(it.toByte(), (it shr 8).toByte()) else listOf(it.toByte()) }
    val file = File.createTempFile("filmstrip-ycbcr", ".y4m")
    try {
      file.writeBytes(header.encodeToByteArray() + body.toByteArray())
      return use(ImageSource.of(file.absolutePath))
    } finally {
      file.delete()
    }
  }

  private fun resolvedImage(
    spec: ImageOverlay,
    attributes: Attributes,
  ): ImageSource {
    val resolution = BuiltInEffectResolver(decoder).resolve(spec, CAPABILITIES, attributes)
    return resolution
      .shouldBeInstanceOf<EffectResolution.Resolved>()
      .effect.fragment.auxInputs
      .first()
      .image
  }

  private companion object {
    val decoder = FfmpegPictureDecoder(hostFfmpeg)
    val FRAME = Size(640, 360)
    val AVIF_GRID = Size(16, 16)
    val HEIC_GRID = Size(128, 128)
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

    val AVIF_STILLS = ColorPictures.AVIFS + ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA
    val HEIC_STILLS = ColorPictures.HEICS + ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA

    const val BT709 = 1
    const val UNSPECIFIED = 2

    // ffmpeg's limit, as read on 9.0: limited mid grey and white at ten bits, and what each reads at sixteen.
    const val MID_LUMA = 502
    const val MID_READ = 32641
    const val WHITE_LUMA = 940
    const val WHITE_READ = 65283

    // ffmpeg's limit at full range, as read on 9.0: ten-bit white and what it reads at sixteen bits.
    const val FULL_WHITE_LUMA = 1023
    const val FULL_WHITE_READ = 65472

    const val DISPLAY_P3_PRIMARIES = 12
    const val SRGB_TRANSFER = 13

    // Limited mid grey 502 at sixteen bits, (502 - 64) / 876 of full scale.
    const val EXACT_MID = 32768
    const val TEN_BIT_MID_CHROMA = 512
    const val EIGHT_BIT_MID_CHROMA = 128
    const val EIGHT_BIT_WHITE = 235
  }
}

private class Grid(
  val name: String,
  val bytes: ByteArray,
  val size: Size,
  val nclx: CicpTag?,
  val ycbcr: List<Int>,
)
