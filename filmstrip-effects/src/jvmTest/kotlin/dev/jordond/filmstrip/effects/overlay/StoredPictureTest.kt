package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.FakeDecoder
import dev.jordond.filmstrip.effects.LINEAR_COLOR
import dev.jordond.filmstrip.effects.argbOf
import dev.jordond.filmstrip.effects.decodedOf
import dev.jordond.filmstrip.effects.pngOf
import dev.jordond.filmstrip.effects.rgbOf
import dev.jordond.filmstrip.effects.tagged
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the ffmpeg backend reads an overlay picture's stored samples, the picture an SDR grade's graph opens for it, and
 * the per-texel reads the HDR mapping runs on.
 */
class StoredPictureTest {
  @Test
  fun `an eight bit picture is read off its raster as the codes it stores`() {
    val image = rgbOf(TEXELS)

    image.storedSamples().shouldBeInstanceOf<StoredSamples.Argb>().argb shouldBe TEXELS
  }

  @Test
  fun `a grey picture is read as its one sample in every color channel`() {
    val image = BufferedImage(3, 1, BufferedImage.TYPE_BYTE_GRAY)
    image.raster.setPixels(0, 0, 3, 1, intArrayOf(0, 0x80, 0xFF))

    val argb = image.storedSamples().shouldBeInstanceOf<StoredSamples.Argb>().argb
    argb shouldBe intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())
  }

  @Test
  fun `a deep picture is read at sixteen bits with no step lost`() {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_USHORT_GRAY)
    image.raster.setSample(0, 0, 0, DEEP_GREY)

    val rgba = image.storedSamples().shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
    rgba.map { it.toInt() and 0xFFFF } shouldBe listOf(DEEP_GREY, DEEP_GREY, DEEP_GREY, 0xFFFF)
  }

  @Test
  fun `a deep picture with alpha keeps each band at its own depth`() {
    val model = ComponentColorModel(SRGB, true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT)
    val image = BufferedImage(model, model.createCompatibleWritableRaster(1, 1), false, null)
    image.raster.setPixel(0, 0, intArrayOf(0x1234, 0x8001, 0xFEDC, 0x4321))

    val rgba = image.storedSamples().shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
    rgba.map { it.toInt() and 0xFFFF } shouldBe listOf(0x1234, 0x8001, 0xFEDC, 0x4321)
  }

  @Test
  fun `a premultiplied picture is read back as its straight color`() {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE)
    image.setRGB(0, 0, HALF_ORANGE)

    val read =
      image
        .storedSamples()
        .shouldBeInstanceOf<StoredSamples.Argb>()
        .argb
        .single()
    (read ushr 24) shouldBe (HALF_ORANGE ushr 24)
    listOf(16, 8, 0).forEach { shift ->
      val gap = (read shr shift and 0xFF) - (HALF_ORANGE shr shift and 0xFF)
      withClue("channel at $shift is $gap off") { (gap in -1..1) shouldBe true }
    }
  }

  @Test
  fun `a palette picture is read through the entries it stores`() {
    val image = BufferedImage(TEXELS.size, 1, BufferedImage.TYPE_BYTE_INDEXED)
    image.setRGB(0, 0, TEXELS.size, 1, TEXELS, 0, TEXELS.size)

    val argb = image.storedSamples().shouldBeInstanceOf<StoredSamples.Argb>().argb
    argb shouldBe image.getRGB(0, 0, TEXELS.size, 1, null, 0, TEXELS.size)
  }

  @Test
  fun `a picture whose color model is neither RGB nor grey has no stored samples`() {
    val model = ComponentColorModel(Cmyk, false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE)
    val image = BufferedImage(model, model.createCompatibleWritableRaster(2, 1), false, null)

    image.storedSamples().shouldBeNull()
  }

  @Test
  fun `a texel's color key leaves out its alpha and tells apart every sixteen bit color`() {
    val packed = StoredSamples.Argb(intArrayOf(0xFF102030.toInt(), 0x10102030, 0xFF302010.toInt()))
    val wide = StoredSamples.Rgba16(shortArrayOf(1, 0, 0, -1, 0, 1, 0, -1, 1, 0, 0, 0x10, 2, 0, 0, -1))

    packed.texelCount shouldBe 3
    packed.colorKey(1) shouldBe packed.colorKey(0)
    packed.colorKey(2) shouldNotBe packed.colorKey(0)
    wide.texelCount shouldBe 4
    (0 until 4).map { wide.colorKey(it) }.distinct().size shouldBe 3
    wide.colorKey(2) shouldBe wide.colorKey(0)
    wide.colorKey(1) shouldNotBe wide.colorKey(0)
    wide.colorKey(3) shouldNotBe wide.colorKey(0)
  }

  @Test
  fun `an alpha is read as a sixteen bit sample`() {
    val packed = StoredSamples.Argb(intArrayOf(0x80000000.toInt(), 0x00FFFFFF, 0xFF000000.toInt()))
    val wide = StoredSamples.Rgba16(shortArrayOf(0, 0, 0, 0x4321, 0, 0, 0, -1))

    (0 until 3).map(packed::wideAlpha) shouldBe listOf(0x8080, 0, 0xFFFF)
    (0 until 2).map(wide::wideAlpha) shouldBe listOf(0x4321, 0xFFFF)
  }

  @Test
  fun `the texels picked are made opaque in the order asked`() {
    val packed = StoredSamples.Argb(intArrayOf(0x00102030, 0x80405060.toInt(), 0xFF708090.toInt()))
    val wide = StoredSamples.Rgba16(shortArrayOf(1, 2, 3, 0, 4, 5, 6, 0x10))

    val picked = packed.opaqueAt(intArrayOf(2, 0)).shouldBeInstanceOf<StoredSamples.Argb>().argb
    picked shouldBe intArrayOf(0xFF708090.toInt(), 0xFF102030.toInt())
    val widePicked = wide.opaqueAt(intArrayOf(1)).shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
    widePicked shouldBe shortArrayOf(4, 5, 6, -1)
  }

  @Test
  fun `a JPEG XL is told by its codestream or its container signature`() {
    val container = byteArrayOf(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)

    isJpegXl(byteArrayOf(0xFF.toByte(), 0x0A, 0)) shouldBe true
    isJpegXl(container + ByteArray(4)) shouldBe true
    isJpegXl(container.copyOf(11)) shouldBe false
    isJpegXl(OrientedPictures.png()) shouldBe false
    isJpegXl(ByteArray(0)) shouldBe false
  }

  @Test
  fun `the encoded bytes are read from a path or file URL and are out of reach for anything else`() {
    val png = OrientedPictures.png()
    val file = createTempFile(suffix = ".png").toFile().apply { writeBytes(png) }
    try {
      ImageSource.Path(file.path).encodedBytes() shouldBe png
      ImageSource.Uri("file://${file.absolutePath}").encodedBytes() shouldBe png
      ImageSource.ofBytes(png).encodedBytes() shouldBe png
      ImageSource.Path(File(file.parentFile, "missing-${file.name}").path).encodedBytes().shouldBeNull()
      withClue("a directory read as a picture") { ImageSource.Path(file.parent).encodedBytes().shouldBeNull() }
      ImageSource.Uri("https://example.invalid/overlay.png").encodedBytes().shouldBeNull()
    } finally {
      file.delete()
    }
  }

  @Test
  fun `without a decoder a picture the JDK reads is read with the color its container describes`() {
    val png = tagged(pngOf(rgbOf(TEXELS)), LINEAR_PROFILE)

    val picture = storedPicture(ImageSource.ofBytes(png), null).shouldBeInstanceOf<StoredRead.Read>().picture
    picture.size shouldBe Size(TEXELS.size, 1)
    picture.samples.srgbCodes(picture.color) shouldBe pictureSrgbCodes(pictureColor(png), TEXELS)
    picture.samples.shouldBeInstanceOf<StoredSamples.Argb>().argb shouldBe TEXELS
    storedPicture(ImageSource.ofBytes(UNREADABLE), null) shouldBeSameInstanceAs StoredRead.Unread
  }

  @Test
  fun `a decoder's answer is carried through as what reading the picture came to`() {
    val image = ImageSource.ofBytes(UNREADABLE)
    val refused = FakeDecoder(size = null, reason = REASON)
    val undrawn =
      object : OverlayPictureDecoder by refused {
        override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> =
          OverlayPictureDecode.Undrawn(REASON)
      }

    storedPicture(image, refused).shouldBeInstanceOf<StoredRead.Undecoded>().reason shouldBe REASON
    storedPicture(image, undrawn).shouldBeInstanceOf<StoredRead.Undrawn>().reason shouldBe REASON
  }

  // Only a JPEG XL keeps its color where the container reader cannot see it, and bytes out of reach have no container.
  @Test
  fun `a decode's own color is taken for a JPEG XL alone and refused where no container can be read`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOR))
    val jpegXl = byteArrayOf(0xFF.toByte(), 0x0A) + ByteArray(16)

    readOf(ImageSource.ofBytes(jpegXl), decoder).color shouldBeSameInstanceAs LINEAR_COLOR
    readOf(ImageSource.ofBytes(UNREADABLE), decoder).color.shouldBeNull()
    storedPicture(UNREACHABLE, decoder) shouldBeSameInstanceAs StoredRead.Unseen
    readOf(UNREACHABLE, FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), null))).color.shouldBeNull()
  }

  @Test
  fun `an untagged picture reaches the graph as it is with the turn its bytes are tagged with`() {
    for (orientation in PictureOrientation.entries) {
      val image = ImageSource.ofBytes(OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif))

      val drawn = sdrOverlayImage(image, null).shouldBeInstanceOf<SdrOverlayImage.Drawn>()
      withClue("$orientation") { drawn.image shouldBeSameInstanceAs image }
      withClue("$orientation") { drawn.orientation shouldBe orientation }
      withClue("$orientation was decoded") { drawn.size.shouldBeNull() }
    }
  }

  // The PNG of its codes is laid out already, so the graph turns it no further.
  @Test
  fun `a tagged turned picture reaches the graph as a PNG of its codes laid out as it is shown`() {
    for (orientation in listOf(
      PictureOrientation.Rotate90,
      PictureOrientation.FlipVertical,
      PictureOrientation.Transverse,
    )) {
      val png = OrientedPictures.taggedPng(tagged(OrientedPictures.png(), LINEAR_PROFILE), orientation.exif)

      val drawn = sdrOverlayImage(ImageSource.ofBytes(png), null).shouldBeInstanceOf<SdrOverlayImage.Drawn>()
      val shown = orientedSamples(orientation, OrientedPictures.samples(), OrientedPictures.SIZE)
      withClue("$orientation") { drawn.orientation shouldBe PictureOrientation.Normal }
      withClue("$orientation") { drawn.size shouldBe orientation.orientedSize(OrientedPictures.SIZE) }
      val bytes = drawn.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes
      withClue("$orientation") { argbOf(bytes) shouldBe pictureSrgbCodes(pictureColor(png), shown) }
    }
  }

  // An sRGB profile lands every texel where an untagged read does, so there is nothing for a PNG of the codes to fix.
  @Test
  fun `a picture tagged sRGB reaches the graph as it is and keeps its turn`() {
    val png =
      OrientedPictures.taggedPng(
        tagged(OrientedPictures.png(), SRGB_PROFILE),
        PictureOrientation.Rotate270.exif,
      )
    val image = ImageSource.ofBytes(png)

    val drawn = sdrOverlayImage(image, null).shouldBeInstanceOf<SdrOverlayImage.Drawn>()
    drawn.image shouldBeSameInstanceAs image
    drawn.orientation shouldBe PictureOrientation.Rotate270
    drawn.size shouldBe PictureOrientation.Rotate270.orientedSize(OrientedPictures.SIZE)
  }

  @Test
  fun `a format outside the content probes whose decode is refused says so with the decoder's reason`() {
    val refused = FakeDecoder(size = null, reason = REASON)

    val read = sdrOverlayImage(ImageSource.ofBytes(WBMP), refused).shouldBeInstanceOf<SdrOverlayImage.Refused>()

    read.message shouldContain "cannot recognise from its bytes alone"
    read.message shouldContain REASON
  }

  @Test
  fun `a format outside the content probes is decoded from a copy named for it with the same bytes`() {
    val file = File.createTempFile("badge", ".wbmp").apply { writeBytes(WBMP) }
    var named: String? = null
    var copied: ByteArray? = null
    val decoder =
      object : OverlayPictureDecoder {
        override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> {
          val path = image.shouldBeInstanceOf<ImageSource.Path>().path
          named = path
          copied = File(path).readBytes()
          return OverlayPictureRead.Read(decodedOf(rgbOf(TEXELS), null))
        }

        override fun measure(image: ImageSource): OverlayPictureRead<Size> = error("nothing to measure")
      }

    try {
      sdrOverlayImage(ImageSource.of(file.path), decoder).shouldBeInstanceOf<SdrOverlayImage.Drawn>()

      File(checkNotNull(named)).extension shouldBe "wbmp"
      withClue("the copy outlived the decode") { File(checkNotNull(named)).exists() shouldBe false }
      copied shouldBe WBMP
    } finally {
      file.delete()
    }
  }

  @Test
  fun `a deep decode that lands where it is stored reaches the graph with its sixteen bit samples`() {
    val samples = shortArrayOf(0x289F.toShort(), 0x7FFF, 0xC934.toShort(), 0xFFFF.toShort())
    val decoder =
      object : OverlayPictureDecoder {
        override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> =
          OverlayPictureRead.Read(DecodedPicture(Size(1, 1), StoredSamples.Rgba16(samples), null))

        override fun measure(image: ImageSource): OverlayPictureRead<Size> = error("nothing to measure")
      }

    val drawn =
      sdrOverlayImage(ImageSource.ofBytes(UNREADABLE), decoder, UNREADABLE).shouldBeInstanceOf<SdrOverlayImage.Drawn>()

    val png = drawn.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes
    pictureColor(png).shouldBeNull()
    val read = ImageIO.read(png.inputStream())
    read.raster.dataBuffer.dataType shouldBe DataBuffer.TYPE_USHORT
    read.storedSamples().shouldBeInstanceOf<StoredSamples.Rgba16>().rgba shouldBe samples
  }

  @Test
  fun `a format outside the content probes is refused by name without a decoder`() {
    for (bytes in listOf(WBMP, gifOf())) {
      val file = File.createTempFile("badge", ".picture").apply { writeBytes(bytes) }
      try {
        val refused = sdrOverlayImage(ImageSource.of(file.path), null).shouldBeInstanceOf<SdrOverlayImage.Refused>()

        refused.message shouldContain file.path
      } finally {
        file.delete()
      }
    }
  }

  @Test
  fun `a GIF is drawn from its decode rather than from bytes the graph cannot repeat`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), null))

    val drawn = sdrOverlayImage(ImageSource.ofBytes(gifOf()), decoder).shouldBeInstanceOf<SdrOverlayImage.Drawn>()

    decoder.decodes.size shouldBe 1
    drawn.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldNotBe gifOf()
  }

  @Test
  fun `a BMP draws as its own bytes without a decoder`() {
    val bmp = encoded("bmp")

    val drawn = sdrOverlayImage(ImageSource.ofBytes(bmp), null).shouldBeInstanceOf<SdrOverlayImage.Drawn>()

    drawn.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldBe bmp
  }

  @Test
  fun `a corrupt picture is refused by name at lowering with or without a decoder`() {
    val png = pngOf(rgbOf(TEXELS))
    val corrupt = png.copyOf().also { it.fill(CORRUPT, PNG_SIGNATURE, png.size) }
    val file = File.createTempFile("corrupt", ".png").apply { writeBytes(corrupt) }
    try {
      for (decoder in listOf(null, FakeDecoder(size = null, reason = REASON))) {
        val refused = sdrOverlayImage(ImageSource.of(file.path), decoder).shouldBeInstanceOf<SdrOverlayImage.Refused>()

        refused.message shouldContain file.path
      }
    } finally {
      file.delete()
    }
  }

  // One byte past the ceiling, sparse, so a read that did not stop at the file's length would cost a whole gigabyte.
  @Test
  fun `a file past the picture ceiling is refused by name without being read`() {
    val file = File.createTempFile("huge", ".png")
    try {
      RandomAccessFile(file, "rw").use { it.setLength(PICTURE_PEAK_CEILING + 1) }
      val spec = ImageOverlay(ImageSource.of(file.path), Corner.TopStart)

      spec.image.encodedBytes().shouldBeNull()
      val refused =
        filterGraphOverlay(spec, ATTRIBUTES, null).shouldBeInstanceOf<EffectResolution.Unsupported>()
      refused.message shouldBe pictureTooLargeToRead(spec.image)
      refused.message shouldContain file.path
    } finally {
      file.delete()
    }
  }

  @Test
  fun `the encoded bytes are held up to the ceiling and refused one past it`() {
    val file = File.createTempFile("ceiling", ".bin")
    try {
      val encoded = ByteArray(32) { it.toByte() }
      file.writeBytes(encoded)
      val image = ImageSource.of(file.path)

      image.encodedRead(encoded.size.toLong()).bytes shouldBe encoded
      image.encodedRead(encoded.size * 2L).bytes shouldBe encoded
      val past = image.encodedRead(encoded.size - 1L)
      past.bytes.shouldBeNull()
      past.refusal shouldBe pictureTooLargeToRead(image, encoded.size - 1L)
    } finally {
      file.delete()
    }
  }

  @Test
  fun `one lowering reads a picture named twice once and the next lowering reads it again`() {
    val file = File.createTempFile("named-twice", ".png")
    val first = pngOf(rgbOf(TEXELS))
    val second = pngOf(rgbOf(intArrayOf(0xFF102030.toInt())))
    file.writeBytes(first)
    val image = ImageSource.of(file.path)
    val composition =
      Composition(
        tracks = listOf(Track(emptyList())),
        effects = listOf(ImageOverlay(image, Corner.TopStart), ImageOverlay(image, Corner.BottomEnd)),
      )
    val reads = mutableListOf<ByteArray?>()
    val pictures = LoweringPictures<HeldBytes> { HeldBytes(it.encodedBytes().also(reads::add)) }
    try {
      pictures.readFor(composition)
      file.writeBytes(second)

      pictures[image].bytes shouldBe first
      pictures[image].bytes shouldBe first
      reads.size shouldBe 1

      pictures.readFor(composition)

      pictures[image].bytes shouldBe second
      reads.size shouldBe 2
    } finally {
      file.delete()
    }
  }

  private fun gifOf(): ByteArray = encoded("gif")

  private fun encoded(format: String): ByteArray {
    val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
    return ByteArrayOutputStream()
      .also { withClue("the JDK wrote no $format") { ImageIO.write(image, format, it) shouldBe true } }
      .toByteArray()
  }

  private fun readOf(
    image: ImageSource,
    decoder: OverlayPictureDecoder,
  ) = storedPicture(image, decoder).shouldBeInstanceOf<StoredRead.Read>().picture

  // A CMYK color space, which only the color model's type is read off.
  private object Cmyk : ColorSpace(ColorSpace.TYPE_CMYK, 4) {
    private fun readResolve(): Any = Cmyk

    override fun toRGB(colorvalue: FloatArray): FloatArray = FloatArray(3)

    override fun fromRGB(rgbvalue: FloatArray): FloatArray = FloatArray(4)

    override fun toCIEXYZ(colorvalue: FloatArray): FloatArray = FloatArray(3)

    override fun fromCIEXYZ(colorvalue: FloatArray): FloatArray = FloatArray(4)
  }

  private companion object {
    val FRAME = Size(64, 64)
    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = dev.jordond.filmstrip.media.ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )
    val SRGB: ColorSpace = ColorSpace.getInstance(ColorSpace.CS_sRGB)
    val SRGB_PROFILE: ByteArray = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data
    val LINEAR_PROFILE: ByteArray = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).data
    val TEXELS = intArrayOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0x8040A040.toInt(), 0x001E90FF)
    const val DEEP_GREY = 0x1235
    val HALF_ORANGE = 0x80FF8000.toInt()
    const val REASON = "Invalid data found when processing input"
    val UNREADABLE = "not a picture the JDK reads".encodeToByteArray()
    val WBMP = byteArrayOf(0, 0, 8, 1, 0x7F)
    const val CORRUPT: Byte = 0x55
    const val PNG_SIGNATURE = 8
    val UNREACHABLE = ImageSource.Uri("https://example.invalid/overlay.png")
  }
}
