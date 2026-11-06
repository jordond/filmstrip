package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How an overlay picture the SDR graph opens as it is gets sized on the ffmpeg backend.
 *
 * The JDK reads the size out of the picture's header and this ffmpeg is asked only for a format the JDK has no reader
 * for. Every expected size is the one this ffmpeg's decoder measures, so a header read that lands anywhere else fails.
 */
class FfmpegOverlaySizeTest {
  private val fixture = FfmpegExportFixture()
  private val directory: File = Files.createTempDirectory("filmstrip-overlay-size").toFile()

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  @Test
  fun `a PNG and a JPEG are sized by one measure at the size this ffmpeg decodes them to`() {
    if (!fixture.available()) return
    val pictures =
      listOf(
        "still.png" to pngOf(OPAQUE, MID),
        "still.jpg" to jpegOf(MID),
      )
    for ((name, bytes) in pictures) {
      val image = ImageSource.of(File(directory, name).apply { writeBytes(bytes) }.path)
      val measured = decoder.measure(image).shouldBeInstanceOf<OverlayPictureRead.Read<Size>>().value
      val counting = CountingDecoder(decoder)
      val spec = ImageOverlay(image, Corner.TopStart)

      withClue(name) { scaleOf(spec, counting) shouldBe scaleFor(spec, measured) }
      withClue("$name measures") { counting.measures shouldBe 1 }
      withClue("$name spawned a decode") { counting.decodes shouldBe 0 }
    }
  }

  // ffmpeg decodes a GIF to its logical screen, so a first frame smaller than the screen does not size it.
  @Test
  fun `a GIF is sized by its logical screen from one decode`() {
    if (!fixture.available()) return
    val image = ImageSource.of(File(directory, "small-frame.gif").apply { writeBytes(SMALL_FRAME_GIF) }.path)
    val counting = CountingDecoder(decoder)
    val spec = ImageOverlay(image, Corner.TopStart)

    scaleOf(spec, counting) shouldBe scaleFor(spec, GIF_SCREEN)
    counting.decodes shouldBe 1
    counting.measures shouldBe 0
  }

  @Test
  fun `a WebP the JDK has no reader for is measured by this ffmpeg`() {
    if (!fixture.available()) return
    val webp = encoded(pngOf(OPAQUE, MID), "webp", listOf("-c:v", "libwebp", "-lossless", "1")) ?: return
    val image = ImageSource.of(webp.absolutePath)
    val counting = CountingDecoder(decoder)
    val spec = ImageOverlay(image, Corner.TopStart)

    scaleOf(spec, counting) shouldBe scaleFor(spec, MID)
    counting.measures shouldBe 1
  }

  private fun scaleOf(
    spec: ImageOverlay,
    reader: OverlayPictureDecoder,
  ): FilterNode =
    BuiltInEffectResolver(reader)
      .resolve(spec, CAPABILITIES, sdrAttributes())
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

  private fun sdrAttributes(): Attributes =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = RATE,
      span = TimeRange.of(Duration.ZERO, 2.seconds),
    )

  private fun jpegOf(size: Size): ByteArray {
    val file = File(directory, "written.jpg")
    val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB)
    withClue("the JDK wrote no JPEG") { ImageIO.write(image, "jpg", file) shouldBe true }
    return file.readBytes()
  }

  // Counts what the catalogue asked [inner] for, each of which is an ffmpeg spawned.
  private class CountingDecoder(
    private val inner: OverlayPictureDecoder,
  ) : OverlayPictureDecoder {
    var measures = 0
    var decodes = 0

    override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> {
      decodes++
      return inner.decode(image)
    }

    override fun measure(image: ImageSource): OverlayPictureRead<Size> {
      measures++
      return inner.measure(image)
    }
  }

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
    val FRAME = Size(640, 360)
    const val RATE = 30f
    const val OPAQUE = 0xFF4080C0.toInt()
    val GIF_SCREEN = Size(5, 3)

    // Off every power of two and unequal sides, so a size read off the wrong field or turned would show.
    val MID = Size(333, 187)

    // A GIF89a whose 5 by 3 screen holds one 2 by 1 frame at its top left, two LZW codes of colour 0 between a clear
    // and an end of information code.
    val SMALL_FRAME_GIF: ByteArray =
      "GIF89a".encodeToByteArray() +
        byteArrayOf(5, 0, 3, 0, 0x80.toByte(), 0, 0) +
        byteArrayOf(0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
        byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 1, 0, 0) +
        byteArrayOf(2, 2, 0x04, 0x0A, 0, 0x3B)
  }
}
