package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The overlay picture's header, read off an [ImageSource.Uri].
 *
 * An animated overlay is sized against the picture's own pixels, so this path opens the file the
 * graph will open. It reads a `file:` URL through the same [dev.jordond.filmstrip.media.filePathOf]
 * the export backend resolves its paths with, which is what keeps a picked file from lowering on one
 * side and failing to open on the other.
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

    assertIs<EffectResolution.Resolved>(resolution)
  }

  // The picker spells a name the JVM writes as %C3%A9, and the file on disk carries the character
  // itself.
  @Test
  fun `sizes an animated overlay named by a non-ascii file uri`() {
    val file = writePng("café.png")

    val resolution = resolve(ImageSource.ofUri(file.toURI().toString()))

    assertIs<EffectResolution.Resolved>(resolution)
  }

  @Test
  fun `sizes an animated overlay named by a plain path`() {
    val file = writePng("plain.png")

    assertIs<EffectResolution.Resolved>(resolve(ImageSource.of(file.path)))
  }

  // Reading the encoding literally opens a file that is not there, and this backend has nothing to
  // size the animation against, so it says so rather than guessing.
  @Test
  fun `refuses an animated overlay whose file uri names nothing`() {
    val resolution = resolve(ImageSource.ofUri("file://$directory/no%20such%20logo.png"))

    assertIs<EffectResolution.Unsupported>(resolution)
    assertTrue(resolution.message.contains("header"), resolution.message)
  }

  // The JVM has no content resolver, so a scheme it cannot open is refused the same way an
  // unreadable file is rather than being read as a path.
  @Test
  fun `refuses an animated overlay named by a uri this platform cannot open`() {
    val resolution = resolve(ImageSource.ofUri("content://media/external/images/1"))

    assertIs<EffectResolution.Unsupported>(resolution)
    assertTrue(resolution.message.contains("header"), resolution.message)
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
  }
}
