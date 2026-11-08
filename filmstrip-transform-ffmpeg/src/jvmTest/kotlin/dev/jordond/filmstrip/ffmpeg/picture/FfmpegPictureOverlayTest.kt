package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.color.ycbcrCodes
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.UNASSEMBLED_REASON
import dev.jordond.filmstrip.ffmpeg.internal.picture.fullRangeAlpha
import dev.jordond.filmstrip.ffmpeg.internal.picture.overlayAssembly
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRuns
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.heif.HeifCanvasFill
import dev.jordond.filmstrip.picture.heif.HeifOverlay
import dev.jordond.filmstrip.picture.heif.HeifOverlayInput
import dev.jordond.filmstrip.picture.heif.HeifOverlayRead
import dev.jordond.filmstrip.picture.heif.heifAlpha
import dev.jordond.filmstrip.picture.heif.heifCodedImages
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import dev.jordond.filmstrip.test.AlphaPlanes
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifOverlays
import dev.jordond.filmstrip.test.HeifPicture
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HeifThumbnail
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * AVIF and HEIC stills whose primary item is an `iovl`, decoded by this ffmpeg.
 *
 * Each canvas is three inputs wide and two high, with three copies of one flat still placed so that some of the canvas
 * shows its fill, including texels in the middle column. A texel an input covers holds what the still alone decodes
 * to, and one none covers holds the canvas color within [PICTURE_DRIFT].
 */
class FfmpegPictureOverlayTest {
  private val fixture = FfmpegExportFixture()
  private val directory = createTempDirectory("filmstrip-overlay").toFile()

  @Test
  fun `an AVIF overlay draws each input at its offset over its canvas color`() {
    if (!fixture.available()) return
    assertOverlay("AVIF", "avif", ColorPictures.NCLX_BT709_LIMITED)
  }

  @Test
  fun `a HEIC overlay draws each input at its offset over its canvas color`() {
    if (!fixture.available()) return
    assertOverlay("HEIC", "heic", ColorPictures.HEIC_NCLX_BT709_LIMITED)
  }

  @Test
  fun `an input that starts above and left of the canvas is cut at its edge`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val bytes = overlay("avif", picture, listOf(-side / 2 to -side / 2), Size(side, side), wide = true)
    val drawn = decoded(bytes)
    drawn.size shouldBe Size(side, side)
    withClue("the top left, inside the input") { texel(drawn, 0, 0) shouldBe centreOf(decoded(picture.bytes())) }
    withClue("the bottom right, past the input") { assertFill(texel(drawn, side - 1, side - 1)) }
  }

  @Test
  fun `an overlay of hundreds of inputs places one in the middle of its mosaic`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val cells = (0..MOSAIC_INPUTS).filter { it != MOSAIC_GAP }
    val offsets = cells.map { (it % MOSAIC_COLUMNS) * side to (it / MOSAIC_COLUMNS) * side }
    val canvas = Size(MOSAIC_COLUMNS * side, MOSAIC_ROWS * side)
    val drawn = decoded(overlay("avif", picture, offsets, canvas))
    val input = centreOf(decoded(picture.bytes()))
    val centre = { cell: Int ->
      texel(drawn, (cell % MOSAIC_COLUMNS) * side + side / 2, (cell / MOSAIC_COLUMNS) * side + side / 2)
    }
    drawn.size shouldBe canvas
    withClue("the input before the gap") { centre(MOSAIC_GAP - 1) shouldBe input }
    withClue("the gap") { assertFill(centre(MOSAIC_GAP)) }
    withClue("the input after the gap") { centre(MOSAIC_GAP + 1) shouldBe input }
    withClue("the last input") { centre(MOSAIC_INPUTS) shouldBe input }
  }

  @Test
  fun `a later input lands over an earlier one where they overlap across two runs`() {
    if (!fixture.available()) return
    val edge = EDGE.thumbnail()
    // An av1C that differs only in its presentation delay, which no decoder reads, so the edge input runs on its own.
    val delayed = edge.configuration.copyOf().also { it[AV1C_DELAY] = PRESENTATION_DELAY }
    val pictures = listOf(FLAT.thumbnail(), HeifThumbnail(edge.itemType, delayed, edge.codestream, edge.size))
    val side = pictures.first().size.width
    for (images in listOf(pictures, pictures.reversed())) {
      val offsets = listOf(0 to 0, side / 2 to 0)
      val canvas = Size(3 * side / 2, side)
      val bytes = HeifOverlays.overlay("avif", images[0], offsets, canvas, nclx = EDGE.nclx, images = images)
      val items =
        heifPrimaryOverlay(bytes)
          .shouldBeInstanceOf<HeifOverlayRead.Read>()
          .overlay.inputs
          .map { it.item }
      withClue("one run an input") { pictureRuns(heifCodedImages(bytes, items).shouldNotBeNull())?.size shouldBe 2 }
      val drawn = decoded(bytes)
      val first = texel(drawn, 1, side / 2)
      val second = texel(drawn, 3 * side / 2 - 2, side / 2)
      withClue("the two inputs decode apart") { (first == second) shouldBe false }
      withClue("the middle of the overlap") { texel(drawn, 3 * side / 4, side / 2) shouldBe second }
    }
  }

  @Test
  fun `inputs left of and above the canvas land at their offsets`() {
    if (!fixture.available()) return
    val images = listOf(FLAT.thumbnail(), EDGE.thumbnail())
    val side = images.first().size.width
    val overlay = { offsets: List<Pair<Int, Int>>, canvas: Size ->
      decoded(HeifOverlays.overlay("avif", images[0], offsets, canvas, FILL_WIDE, EDGE.nclx, images = images))
    }
    val apart = overlay(listOf(0 to 0, side to 0), Size(2 * side, side))
    val first = texel(apart, side / 2, side / 2)
    val second = texel(apart, side + side / 2, side / 2)
    val shifted = overlay(listOf(-side / 2 to -side / 2, side / 2 to side / 4), Size(2 * side, 2 * side))
    withClue("the first input, cut at the corner") { texel(shifted, 1, 1) shouldBe first }
    withClue("the second input") { texel(shifted, side, side) shouldBe second }
    withClue("below the first, left of the second") { assertFill(texel(shifted, 1, side)) }
    withClue("right of the second") { assertFill(texel(shifted, 2 * side - 1, side / 2)) }
  }

  @Test
  fun `an iovl alpha item lands as the alpha of an iovl picture`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val gray = List(3) { ALPHA_GRAY * WIDE_PER_BYTE } + WIDE
    val bytes =
      HeifOverlays.overlay(
        "avif",
        picture.thumbnail(),
        listOf(0 to 0),
        Size(2 * side, side),
        FILL.map { it * WIDE_PER_BYTE } + WIDE,
        picture.nclx,
        alpha = AlphaPlanes.avif(),
        alphaFill = gray,
      )
    val drawn = decoded(bytes)
    withClue("a texel the alpha input covers") { texel(drawn, 3, 4) ushr 24 shouldBe AlphaPlanes.avifAlpha(3, 4) }
    withClue("a texel only the alpha canvas covers") {
      abs((texel(drawn, side + side / 2, side / 2) ushr 24) - ALPHA_GRAY) shouldBeLessThanOrEqual 1
    }
  }

  @Test
  fun `an iovl alpha canvas of a color reads as the luma its codestream codes that color as`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    for (fullRange in listOf(true, false)) {
      val bytes =
        HeifOverlays.overlay(
          "avif",
          picture.thumbnail(),
          listOf(0 to 0),
          Size(2 * side, side),
          FILL_WIDE,
          picture.nclx,
          alpha = AlphaPlanes.avif(fullRange = fullRange),
          alphaFill = ALPHA_COLOR,
        )
      val alpha = heifAlpha(bytes).shouldNotBeNull()
      val luma = alpha.reading.ycbcrCodes(ALPHA_COLOR[0], ALPHA_COLOR[1], ALPHA_COLOR[2], 8, fullRange).first()
      val wide = luma * WIDE_PER_BYTE
      val expected = ((if (fullRange) wide else fullRangeAlpha(wide, 8)) / WIDE_PER_BYTE.toDouble()).roundToInt()
      val drawn = decoded(bytes)
      withClue("full range $fullRange, luma $luma") {
        texel(drawn, side + side / 2, side / 2) ushr 24 shouldBe expected
        if (fullRange) texel(drawn, 3, 4) ushr 24 shouldBe AlphaPlanes.avifAlpha(3, 4)
      }
    }
  }

  @Test
  fun `a translucent canvas color is drawn with its alpha where no input covers it`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val input = centreOf(decoded(picture.bytes()))
    val half = FILL.map { it * WIDE_PER_BYTE } + HALF_ALPHA
    val layouts = mapOf("one input" to listOf(0 to 0), "two inputs" to listOf(0 to 0, 2 * side to 0))
    for ((name, offsets) in layouts) {
      val drawn = decoded(overlay("avif", picture, offsets, Size(3 * side, side), half))
      withClue("$name, a covered texel") { texel(drawn, side / 2, side / 2) shouldBe input }
      val fill = texel(drawn, side + side / 2, side / 2)
      withClue("$name, the fill's alpha") { fill ushr 24 shouldBe HALF_ALPHA_BYTE }
      withClue("$name, the fill's color") { assertFill(fill or (0xFF shl 24)) }
    }
  }

  @Test
  fun `a translucent canvas color the inputs cover whole leaves the picture opaque`() {
    val size = Size(8, 8)
    val half = HeifCanvasFill(0, 0, 0, HALF_ALPHA)
    val covering = listOf(HeifOverlayInput(2, 0, 0, size), HeifOverlayInput(3, 8, 0, size))
    withClue("covered") {
      overlayAssembly(HeifOverlay(covering, Size(16, 8), half), "null", color = null).filter shouldContain
        "format=rgb24"
    }
    withClue("shown") {
      overlayAssembly(HeifOverlay(covering, Size(17, 8), half), "null", color = null).filter shouldContain
        "format=rgba"
    }
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val fill = FILL.map { it * WIDE_PER_BYTE } + HALF_ALPHA
    val drawn = decoded(overlay("avif", picture, listOf(0 to 0, side to 0), Size(2 * side, side), fill))
    val input = centreOf(decoded(picture.bytes()))
    for (x in listOf(0, side - 1, side, 2 * side - 1)) withClue("x $x") { texel(drawn, x, side / 2) shouldBe input }
  }

  @Test
  fun `an overlay with no inputs is refused rather than assembled`() {
    val bytes = overlay("avif", ColorPictures.NCLX_BT709_LIMITED, emptyList(), Size(8, 8))
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    read.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe UNASSEMBLED_REASON
    val measured = decoder.measure(ImageSource.ofBytes(bytes))
    measured.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe UNASSEMBLED_REASON
  }

  @Test
  fun `an overlay decodes only the inputs that land on its canvas without wrapping`() {
    val fill = HeifCanvasFill(0, 0, 0, 65535)
    val size = Size(8, 8)
    val far = listOf(HeifOverlayInput(2, Int.MIN_VALUE, 0, size), HeifOverlayInput(3, Int.MAX_VALUE - 8, 0, size))
    withClue("none lands, the first decodes for its format alone") {
      overlayAssembly(HeifOverlay(far, Size(8, 8), fill), "null", color = null).layout.items shouldBe listOf(2L)
    }
    val between = listOf(far[0], HeifOverlayInput(4, 0, 0, size), far[1])
    overlayAssembly(HeifOverlay(between, Size(8, 8), fill), "null", color = null).layout.items shouldBe listOf(4L)
  }

  @Test
  fun `an overlay whose only input lands off its canvas reads as its canvas color`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val drawn = decoded(overlay("avif", picture, listOf(30000 to 30000), Size(3 * side, side)))
    drawn.size shouldBe Size(3 * side, side)
    for (x in listOf(0, 3 * side / 2, 3 * side - 1)) withClue("x $x") { assertFill(texel(drawn, x, side / 2)) }
  }

  // A JPEG's samples are full range, which its decoded frames carry and a raw canvas does not.
  @Test
  fun `an untagged grid of JPEG tiles reads at the colors of its tiles decoded alone`() {
    if (!fixture.available()) return
    val side = 16
    val tiles = JPEG_COLORS.map { jpegOf(it, side) }
    val bytes =
      HeifStills.grid(
        "heic",
        "jpeg",
        JPGC,
        tiles[0],
        Size(side, side),
        2,
        1,
        images =
          tiles.map {
            HeifThumbnail("jpeg", JPGC, it, Size(side, side))
          },
      )
    val drawn = decoded(bytes)
    drawn.size shouldBe Size(2 * side, side)
    for ((at, tile) in tiles.withIndex()) {
      val alone = texel(decoded(tile), side / 2, side / 2)
      withClue(
        "tile $at, ${alone.toUInt().toString(16)}",
      ) { texel(drawn, at * side + side / 2, side / 2) shouldBe alone }
    }
  }

  // A flat [color] JPEG [side] texels square, 4:4:4 so no tile edge blurs its chroma, as this ffmpeg encodes it.
  private fun jpegOf(
    color: String,
    side: Int,
  ): ByteArray {
    val source = "color=c=$color:s=${side}x$side"
    val command =
      listOf(hostFfmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", source, "-frames:v", "1") +
        listOf("-pix_fmt", "yuvj444p", "-f", "mjpeg", "-")
    val process = ProcessBuilder(command).start()
    val bytes = process.inputStream.readAllBytes()
    withClue("ffmpeg encodes $color") { process.waitFor() shouldBe 0 }
    return bytes
  }

  @Test
  fun `every child of a picture put together reads one elementary stream and never the file`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val tile = picture.thumbnail()
    val gridAlpha =
      HeifStills.grid(
        "avif",
        tile.itemType,
        tile.configuration,
        tile.codestream,
        tile.size,
        nclx = picture.nclx,
        alpha = List(4) { AlphaPlanes.avifTile(it) },
      )
    val overlayAlpha =
      HeifOverlays.overlay("avif", tile, listOf(0 to 0), Size(8, 8), nclx = picture.nclx, alpha = AlphaPlanes.avif())
    // Each picture and how many children read it: one a run of its color, one for its canvas, and the same again for
    // an alpha put together, or one for an alpha coded as one image.
    val pictures =
      mapOf(
        "an overlay" to (overlay("avif", picture, listOf(0 to 0), Size(8, 8)) to 2),
        "a grid" to (picture.gridBytes() to 2),
        "a grid with a grid alpha" to (gridAlpha to 4),
        "an overlay with an overlay alpha" to (overlayAlpha to 4),
      )
    for ((name, read) in pictures) {
      val (bytes, children) = read
      val arguments = File(directory, "arguments-$name")
      val binary = File(directory, "recording-ffmpeg-$name")
      binary.writeText(
        "#!/bin/sh\nfor a in \"$@\"; do printf '%s ' \"\$a\" >> '${arguments.absolutePath}'; done\n" +
          "echo >> '${arguments.absolutePath}'\nexec ffmpeg \"$@\"\n",
      )
      check(binary.setExecutable(true)) { "could not make $binary executable" }
      val recording = FfmpegPictureDecoder(binary.absolutePath, scratch = directory)
      withClue(name) { recording.decode(ImageSource.ofBytes(bytes)).shouldBeInstanceOf<OverlayPictureRead.Read<*>>() }
      val launches = arguments.readLines().map { it.trim().split(' ') }
      withClue("$name, launches $launches") {
        launches.size shouldBe children
        for (launch in launches) {
          val inputs = launch.indices.filter { launch[it] == "-i" }.map { launch[it - 1] to launch[it + 1] }
          withClue("every input is one elementary stream or a raw canvas") {
            inputs.map { it.first }.filterNot { it in STREAM_FORMATS || CANVAS_SIZE.matches(it) } shouldBe emptyList()
          }
          withClue("the first input is read from stdin") { inputs.first().second shouldBe "-" }
          launch.contains("-max_streams") shouldBe false
        }
      }
    }
  }

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  // [picture] as an overlay of three copies on a canvas three of them wide and two high, checked at an input's corner,
  // the middle of the canvas, the fill beside it and the fill below it.
  private fun assertOverlay(
    name: String,
    brand: String,
    picture: HeifPicture,
  ) {
    val side = picture.thumbnail().size.width
    val canvas = Size(3 * side, 2 * side)
    val bytes = overlay(brand, picture, listOf(0 to 0, 2 * side to side, side to side / 2), canvas)
    val drawn = decoded(bytes)
    val input = centreOf(decoded(picture.bytes()))
    withClue("$name size") { drawn.size shouldBe canvas }
    withClue("$name first input") { texel(drawn, 0, 0) shouldBe input }
    withClue("$name second input") { texel(drawn, 3 * side - 1, 2 * side - 1) shouldBe input }
    val middle = texel(drawn, canvas.width / 2, canvas.height / 2)
    withClue("$name middle, inside the third input") { middle shouldBe input }
    withClue("$name fill right of the middle") { assertFill(texel(drawn, 5 * side / 2, side / 2)) }
    withClue("$name fill below the middle") { assertFill(texel(drawn, canvas.width / 2, 3 * side / 2)) }
    withClue("$name fill at the third input's bottom edge") { assertFill(texel(drawn, side, 3 * side / 2)) }
    val measured = decoder.measure(ImageSource.ofBytes(bytes))
    withClue("$name measured: $measured") { (measured as? OverlayPictureRead.Read)?.value shouldBe canvas }
  }

  private fun overlay(
    brand: String,
    picture: HeifPicture,
    offsets: List<Pair<Int, Int>>,
    canvas: Size,
    fill: List<Int> = FILL.map { it * WIDE_PER_BYTE } + WIDE,
    wide: Boolean = false,
  ): ByteArray = HeifOverlays.overlay(brand, picture.thumbnail(), offsets, canvas, fill, picture.nclx, wide)

  private fun assertFill(argb: Int) {
    for ((channel, expected) in FILL.withIndex()) {
      val read = argb ushr (16 - 8 * channel) and 0xFF
      withClue("channel $channel of ${argb.toUInt().toString(16)}") {
        abs(read - expected) shouldBeLessThanOrEqual PICTURE_DRIFT
      }
    }
    withClue("the fill is opaque") { argb ushr 24 shouldBe 0xFF }
  }

  private fun decoded(bytes: ByteArray): DecodedPicture = decodedBy(decoder, bytes)

  private fun texel(
    decoded: DecodedPicture,
    x: Int,
    y: Int,
  ): Int = texelOf(decoded, x, y)

  private fun centreOf(decoded: DecodedPicture): Int = texel(decoded, decoded.size.width / 2, decoded.size.height / 2)

  private companion object {
    val decoder = FfmpegPictureDecoder(hostFfmpeg)

    // A saturated orange, so a fill painted through another matrix than the picture's lands well off it.
    val FILL = listOf(230, 120, 30)
    val FILL_WIDE = FILL.map { it * WIDE_PER_BYTE } + WIDE

    // Two flat stills that decode to colors far apart, so an input drawn in the other's place reads off it.
    val FLAT = ColorPictures.NCLX_BT709_LIMITED
    val EDGE = ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA
    const val WIDE_PER_BYTE = 257

    // A gray alpha canvas between clear and opaque, so a canvas painted clear or opaque reads off it.
    const val ALPHA_GRAY = 128

    // An alpha canvas color whose channels differ, so a canvas painted from one channel or through another matrix
    // reads off the luma its codestream codes it as.
    val ALPHA_COLOR = listOf(0xC000, 0x3000, 0x9000, WIDE)
    const val WIDE = 65535

    // Half of full scale in sixteen bits, which ffmpeg paints at the nearest eight-bit code.
    const val HALF_ALPHA = 0x8000

    // Where an av1C box keeps its initial presentation delay, past its eight byte header, and a delay to set there.
    const val AV1C_DELAY = 11
    const val PRESENTATION_DELAY: Byte = 0x10

    // Two mid colors far from grey, so a range read wrong moves every channel, and an empty jpgC box.
    val JPEG_COLORS = listOf("0xC07050", "0x3080B0")
    val JPGC = byteArrayOf(0, 0, 0, 8) + "jpgC".encodeToByteArray()
    const val HALF_ALPHA_BYTE = 0x80

    // What ahead of an input's -i names it: an elementary stream's format, or the size of a raw canvas.
    val STREAM_FORMATS = setOf("hevc", "obu", "mjpeg")
    val CANVAS_SIZE = Regex("""\d+x\d+""")

    // Three hundred inputs over a mosaic of twenty columns, with one cell in the middle left to the fill.
    const val MOSAIC_INPUTS = 300
    const val MOSAIC_COLUMNS = 20
    const val MOSAIC_ROWS = 16
    const val MOSAIC_GAP = 151
  }
}
