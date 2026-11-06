package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.measureOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.unreadableImageFile
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The overlay picture's header, which sizes every picture the catalogue does not decode.
 *
 * An overlay is sized against the picture's own pixels, so this path opens the file the graph will open. It reads a
 * `file:` URL through the same [dev.jordond.filmstrip.media.filePathOf] the export backend resolves its paths with,
 * which is what keeps a picked file from lowering on one side and failing to open on the other. The JDK reads the
 * header first, and a backend's decoder measures only a picture the JDK has no reader for.
 */
class OverlayHeaderTest {
  private val directory: File = Files.createTempDirectory("filmstrip-overlay").toFile()

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  @Test
  fun `sizes an animated overlay named by an encoded file uri`() {
    val file = writePng("my logo.png")

    val resolution = resolve(ImageSource.ofUri("file://${file.parent}/my%20logo.png"))

    resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
  }

  // The picker spells a name the JVM writes as %C3%A9, and the file on disk carries the character
  // itself.
  @Test
  fun `sizes an animated overlay named by a non-ascii file uri`() {
    val file = writePng("café.png")

    val resolution = resolve(ImageSource.ofUri(file.toURI().toString()))

    resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
  }

  @Test
  fun `sizes an animated overlay named by a plain path`() {
    val file = writePng("plain.png")

    resolve(ImageSource.of(file.path)).shouldBeInstanceOf<EffectResolution.Resolved>()
  }

  // Reading the encoding literally opens a file that is not there, and this backend has nothing to
  // size the animation against, so it says so rather than guessing.
  @Test
  fun `refuses an animated overlay whose file uri names nothing`() {
    val resolution = resolve(ImageSource.ofUri("file://$directory/no%20such%20logo.png"))

    resolution.shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(resolution.message) { resolution.message.contains("header") shouldBe true }
  }

  // The JVM has no content resolver, so a scheme it cannot open is refused the same way an
  // unreadable file is rather than being read as a path.
  @Test
  fun `refuses an animated overlay named by a uri this platform cannot open`() {
    val resolution = resolve(ImageSource.ofUri("content://media/external/images/1"))

    resolution.shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(resolution.message) { resolution.message.contains("header") shouldBe true }
  }

  @Test
  fun `a PNG and a JPEG still are sized by the frame the decoder finds in the bytes it was handed`() {
    for ((name, format) in listOf("still.png" to "png", "still.jpg" to "jpg")) {
      val decoder = FakeDecoder(size = SQUARE)
      val file = writePicture(name, format, MID)
      val spec = ImageOverlay(ImageSource.of(file.path), Corner.TopStart)

      val resolution = BuiltInEffectResolver(decoder).resolve(spec, capabilities(), attributes())
      withClue(name) { scaleOf(resolution) shouldBe scaleFor(spec, SQUARE) }
      withClue("$name measured") { decoder.measured shouldBe listOf(ImageSource.ofBytes(file.readBytes())) }
      withClue("$name decoded") { decoder.decodes shouldBe emptyList() }
    }
  }

  @Test
  fun `a missing file is refused without asking the decoder`() {
    val missing = File(directory, "gone.png")
    for (image in listOf(ImageSource.of(missing.path), ImageSource.ofUri(missing.toURI().toString()))) {
      val decoder = FakeDecoder(size = SQUARE)
      val spec = ImageOverlay(image, Corner.TopStart)

      val refused =
        BuiltInEffectResolver(decoder)
          .resolve(spec, capabilities(), attributes())
          .shouldBeInstanceOf<EffectResolution.Unsupported>()
      withClue("$image") { refused.message shouldBe unreadableImageFile(image) }
      withClue("$image measured") { decoder.measured shouldBe emptyList() }
      withClue("$image decoded") { decoder.decodes shouldBe emptyList() }
    }
  }

  @Test
  fun `a picture the JDK has no reader for is measured by the decoder`() {
    val decoder = FakeDecoder(size = SQUARE)
    val spec = ImageOverlay(ImageSource.ofBytes(UNTAGGED_WEBP), Corner.TopStart)

    val resolution = BuiltInEffectResolver(decoder).resolve(spec, capabilities(), attributes())
    scaleOf(resolution) shouldBe scaleFor(spec, SQUARE)
    decoder.measured shouldBe listOf(spec.image)
  }

  private fun scaleOf(resolution: EffectResolution?): FilterNode =
    resolution
      .shouldBeInstanceOf<EffectResolution.Resolved>()
      .effect.fragment.auxInputs
      .single()
      .chain
      .first { it.name.startsWith("scale") }

  private fun scaleFor(
    spec: ImageOverlay,
    shown: Size,
  ): FilterNode {
    val drawn = spec.placedOn(FRAME, shown).size
    return FilterNode("scale", "w" to drawn.width.toString(), "h" to drawn.height.toString())
  }

  private fun writePicture(
    name: String,
    format: String,
    size: Size,
  ): File {
    val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB)
    val file = File(directory, name)
    withClue("the JDK wrote no $format") { ImageIO.write(image, format, file) shouldBe true }
    return file
  }

  private fun writePng(name: String): File {
    val image = BufferedImage(SQUARE.width, SQUARE.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, SQUARE.width, SQUARE.height)
    graphics.dispose()

    return File(directory, name).also { ImageIO.write(image, "png", it) }
  }

  private fun resolve(image: ImageSource): EffectResolution? {
    val spec = ImageOverlay(image, Corner.TopStart, animation = fadeIn(1.seconds))

    return BuiltInEffectResolver().resolve(spec, capabilities(), attributes())
  }

  private fun attributes(): Attributes =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = RATE,
      span = TimeRange.of(Duration.ZERO, 2.seconds),
    )

  private fun capabilities(): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.FilterGraph,
      supportsFragmentShader = false,
      supportsComputeShader = false,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = 16_384,
      features = emptySet(),
    )

  private companion object {
    val SQUARE = Size(64, 64)
    val FRAME = Size(640, 360)
    const val RATE = 30f

    // Off every power of two and unequal sides, so a size read off the wrong field or turned would show.
    val MID = Size(333, 187)

    // A RIFF WebP holding an empty lossy chunk and no color description, which the JDK has no reader for.
    val UNTAGGED_WEBP: ByteArray =
      "RIFF".encodeToByteArray() + byteArrayOf(12, 0, 0, 0) + "WEBPVP8 ".encodeToByteArray() + ByteArray(4)
  }
}
