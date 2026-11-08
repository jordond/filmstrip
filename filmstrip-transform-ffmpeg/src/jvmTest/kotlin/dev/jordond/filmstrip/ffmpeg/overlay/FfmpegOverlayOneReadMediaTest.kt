package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.test.IccPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything a lowering draws comes from one read of the picture's bytes, whichever route the picture takes.
 *
 * A picture is swapped from inside the decoder, the window a decoder reading the file again would see the new one in.
 */
class FfmpegOverlayOneReadMediaTest {
  private val root: File = createTempDirectory("filmstrip-one-read").toFile()
  private val decoder = FfmpegPictureDecoder(hostFfmpeg)

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `a tagged picture swapped during its decode keeps the codes and size of its first read`() {
    val first = IccPictures.LINEAR_SRGB
    val picture = File(root, "tagged.png").apply { writeBytes(first.pngOf(FIRST_SIZE)) }
    val spec = ImageOverlay(ImageSource.of(picture.path), Corner.TopStart)
    val swapping = SwappingDecoder(decoder) { picture.writeBytes(pngOf(SWAPPED_COLOR, SWAPPED_SIZE)) }

    val fragment = fragmentOf(spec, swapping)

    swapping.swaps shouldBe 1
    val expected = pictureSrgbCodes(pictureColor(first.pngOf(FIRST_SIZE)), intArrayOf(first.texel)).single()
    val drawn =
      fragment.auxInputs
        .single()
        .image
        .shouldBeInstanceOf<ImageSource.Bytes>()
    pixelsOf(drawn.bytes).let { (size, centre) ->
      size shouldBe FIRST_SIZE
      centre shouldBe expected
    }
    fragment.auxInputs
      .single()
      .chain
      .first { it.name == "scale" }
      .arguments
      .first { it.key == "w" }
      .value shouldBe
      spec
        .placedOn(FRAME, FIRST_SIZE)
        .size.width
        .toString()
  }

  @Test
  fun `an HDR grade decodes the bytes of its first read`() {
    val first = IccPictures.LINEAR_SRGB
    val picture = File(root, "tagged-hdr.png").apply { writeBytes(first.pngOf(FIRST_SIZE)) }
    val spec = ImageOverlay(ImageSource.of(picture.path), Corner.TopStart)
    val swapping = SwappingDecoder(decoder) { picture.writeBytes(pngOf(SWAPPED_COLOR, SWAPPED_SIZE)) }
    val read = mutableListOf<ImageSource>()

    resolution(spec, RecordingDecoder(swapping, read), HdrTransfer.Pq)
      .shouldBeInstanceOf<EffectResolution.Resolved>()

    read.single().shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldBe first.pngOf(FIRST_SIZE)
  }

  // A TGA has no signature, so ffmpeg finds its decoder from the file's extension alone.
  @Test
  fun `a format ffmpeg finds by extension alone decodes under a copy named for it`() {
    val tga = File(root, "badge.tga").apply { writeBytes(tgaOf(TGA_TEXEL, FIRST_SIZE)) }
    val spec = ImageOverlay(ImageSource.of(tga.path), Corner.TopStart)
    val swapping = SwappingDecoder(decoder) { tga.writeBytes(tgaOf(SWAPPED_COLOR, SWAPPED_SIZE)) }

    val drawn =
      fragmentOf(spec, swapping)
        .auxInputs
        .single()
        .image
        .shouldBeInstanceOf<ImageSource.Bytes>()

    swapping.swaps shouldBe 1
    pixelsOf(drawn.bytes) shouldBe (FIRST_SIZE to TGA_TEXEL)
  }

  // The route a format outside ffmpeg's content probes takes must land where ffmpeg opening the file by name does.
  @Test
  fun `a format outside the content probes draws the pixels ffmpeg reads by name`() {
    val tga = File(root, "gradient.tga").apply { writeBytes(tgaGradient(FIRST_SIZE)) }
    val spec = ImageOverlay(ImageSource.of(tga.path), Corner.TopStart)

    val drawn =
      fragmentOf(spec, decoder)
        .auxInputs
        .single()
        .image
        .shouldBeInstanceOf<ImageSource.Bytes>()
    val asPng = File(root, "drawn.png").apply { writeBytes(drawn.bytes) }

    rgbaOf(asPng) shouldBe rgbaOf(tga)
  }

  @Test
  fun `a format ffmpeg content probes reaches the graph as its own bytes`() {
    val ppm = ppmOf(FIRST_SIZE)
    val file = File(root, "badge.ppm").apply { writeBytes(ppm) }
    val spec = ImageOverlay(ImageSource.of(file.path), Corner.TopStart)
    val recording = mutableListOf<ImageSource>()

    val drawn = fragmentOf(spec, RecordingDecoder(decoder, recording)).auxInputs.single().image

    drawn.shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldBe ppm
    withClue("nothing but a measure spawned") { recording.size shouldBe 0 }
  }

  @Test
  fun `a corrupt PNG is refused by name at lowering`() {
    val bytes = pngOf(SWAPPED_COLOR, FIRST_SIZE)
    val at = String(bytes, Charsets.ISO_8859_1).indexOf("IDAT") + 4
    for (index in at until bytes.size - TAIL) bytes[index] = CORRUPT
    val file = File(root, "corrupt.png").apply { writeBytes(bytes) }
    val spec = ImageOverlay(ImageSource.of(file.path), Corner.TopStart)

    val refused = resolution(spec, decoder, null).shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldContain file.path
  }

  private fun fragmentOf(
    spec: ImageOverlay,
    reader: OverlayPictureDecoder,
  ): FilterFragment =
    resolution(spec, reader, null)
      .shouldBeInstanceOf<EffectResolution.Resolved>()
      .effect.fragment

  private fun resolution(
    spec: ImageOverlay,
    reader: OverlayPictureDecoder,
    transfer: HdrTransfer?,
  ): EffectResolution {
    val attributes =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
        hdrTransfer = transfer,
        frameRate = RATE,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )
    return BuiltInEffectResolver(reader).resolve(spec, CAPABILITIES, attributes).let(::checkNotNull)
  }

  private fun pixelsOf(png: ByteArray): Pair<Size, Int> {
    val image = ImageIO.read(ByteArrayInputStream(png))
    return Size(image.width, image.height) to image.getRGB(image.width / 2, image.height / 2)
  }

  private fun rgbaOf(file: File): ByteArray {
    val command =
      listOf(hostFfmpeg, "-nostdin", "-loglevel", "error", "-i", file.path, "-frames:v", "1") +
        listOf("-pix_fmt", "rgba", "-f", "rawvideo", "-")
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val bytes = process.inputStream.readAllBytes()
    withClue("ffmpeg still reading $file") { process.waitFor(EXIT_SECONDS, TimeUnit.SECONDS) shouldBe true }
    return bytes
  }

  private fun tgaOf(
    argb: Int,
    size: Size,
  ): ByteArray = tgaOf(size) { argb }

  private fun tgaGradient(size: Size): ByteArray =
    tgaOf(size) { index ->
      val step = index * GRADIENT_STEP and BYTE_MAX
      (OPAQUE shl ALPHA_SHIFT) or (step shl RED_SHIFT) or ((BYTE_MAX - step) shl GREEN_SHIFT) or GRADIENT_BLUE
    }

  private fun tgaOf(
    size: Size,
    texel: (Int) -> Int,
  ): ByteArray {
    val header = ByteArray(TGA_HEADER)
    header[2] = TGA_TRUE_COLOR
    header[12] = size.width.toByte()
    header[14] = size.height.toByte()
    header[16] = TGA_DEPTH
    header[17] = TGA_TOP_LEFT
    val body =
      ByteArray(size.width * size.height * RGB) { at ->
        val argb = texel(at / RGB)
        (argb shr (at % RGB * BYTE_BITS) and BYTE_MAX).toByte()
      }
    return header + body
  }

  private fun ppmOf(size: Size): ByteArray =
    "P6\n${size.width} ${size.height}\n255\n".encodeToByteArray() +
      ByteArray(size.width * size.height * RGB) { (it * GRADIENT_STEP).toByte() }

  // Runs [swap] the first time [inner] is asked to decode, ahead of that decode.
  private class SwappingDecoder(
    private val inner: OverlayPictureDecoder,
    private val swap: () -> Unit,
  ) : OverlayPictureDecoder {
    var swaps = 0

    override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> {
      if (swaps++ == 0) swap()
      return inner.decode(image)
    }

    override fun measure(image: ImageSource): OverlayPictureRead<Size> = inner.measure(image)
  }

  // Records what the catalogue handed [inner] to decode.
  private class RecordingDecoder(
    private val inner: OverlayPictureDecoder,
    private val decodes: MutableList<ImageSource>,
  ) : OverlayPictureDecoder {
    override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> {
      decodes += image
      return inner.decode(image)
    }

    override fun measure(image: ImageSource): OverlayPictureRead<Size> = inner.measure(image)
  }

  private companion object {
    val FRAME = Size(640, 360)
    val FIRST_SIZE = Size(48, 24)
    val SWAPPED_SIZE = Size(96, 16)
    const val RATE = 30f
    const val SWAPPED_COLOR = 0xFF20C040.toInt()
    const val TGA_TEXEL = 0xFF4080C0.toInt()
    const val TGA_HEADER = 18
    const val TGA_TRUE_COLOR: Byte = 2
    const val TGA_DEPTH: Byte = 24
    const val TGA_TOP_LEFT: Byte = 0x20
    const val RGB = 3
    const val BYTE_BITS = 8
    const val BYTE_MAX = 0xFF
    const val OPAQUE = 0xFF
    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val GRADIENT_STEP = 5
    const val GRADIENT_BLUE = 0x40
    const val CORRUPT: Byte = 0xAA.toByte()
    const val TAIL = 12
    const val EXIT_SECONDS = 30L
  }
}
