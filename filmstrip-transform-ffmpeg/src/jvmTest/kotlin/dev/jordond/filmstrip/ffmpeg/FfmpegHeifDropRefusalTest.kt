package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.heifWithoutOrientation
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicSubsampledPictures
import dev.jordond.filmstrip.test.OrientedPictures
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A tagged HEIC whose `irot` and `imir` drop core refuses, read through a decoder whose ffmpeg does not exist and whose
 * writes fail, so a read that went past the refusal would fail another way. Every route reports core's own sentence.
 */
@OptIn(InternalFilmstripApi::class)
class FfmpegHeifDropRefusalTest {
  private val directory: File = Files.createTempDirectory("filmstrip-heif-drop").toFile()
  private val decoder =
    FfmpegPictureDecoder(MISSING_FFMPEG, scratch = directory) { _, _ -> throw IOException(UNWRITTEN) }
  private val still = HeicSubsampledPictures.EIGHT_BIT_420.bytes(BT709)
  private val tagged = OrientedPictures.taggedHeif(still, quarterTurns = 1, mirrorAxis = null) + MOOV
  private val message = assertIs<UnturnedHeif.Refused>(heifWithoutOrientation(tagged)).message

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  @Test
  fun `a decode and a measure of bytes or a path give core's sentence`() {
    val path = File(directory, "tagged.heic").apply { writeBytes(tagged) }
    for (image in listOf(ImageSource.ofBytes(tagged), ImageSource.of(path.absolutePath))) {
      val undrawn = assertIs<OverlayPictureDecode.Undrawn>(decoder.decode(image), "$image")
      assertEquals(message, undrawn.reason, "$image")
      val refused = assertIs<OverlayPictureRead.Refused>(decoder.measure(image), "$image")
      assertEquals(message, refused.reason, "$image")
    }
  }

  @Test
  fun `an overlay of it is unsupported with core's sentence alone in sdr and hdr`() {
    val resolver = BuiltInEffectResolver(decoder)
    val spec = ImageOverlay(ImageSource.ofBytes(tagged), Corner.TopStart)
    for (transfer in listOf(null, HdrTransfer.Pq)) {
      val attributes =
        Attributes(
          inputSize = FRAME,
          outputSize = FRAME,
          layoutSize = FRAME,
          colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
          hdrTransfer = transfer,
          frameRate = 30f,
          span = TimeRange.of(Duration.ZERO, 2.seconds),
        )

      val unsupported = assertIs<EffectResolution.Unsupported>(resolver.resolve(spec, capabilities, attributes))

      assertEquals(message, unsupported.message, "$transfer")
      assertFalse(TOOLCHAIN_SAID in unsupported.message, "$transfer")
    }
  }

  private companion object {
    const val MISSING_FFMPEG = "/nonexistent/ffmpeg"
    const val UNWRITTEN = "The picture was not written"
    const val TOOLCHAIN_SAID = "The toolchain said"
    val BT709 = CicpTag(1, 13, 1, true)
    val FRAME = Size(640, 360)

    // An empty moov box, past which core refuses to drop a tagged file's irot and imir.
    val MOOV = byteArrayOf(0, 0, 0, 8) + "moov".encodeToByteArray()
    val capabilities =
      RenderCapabilities(
        api = RenderApi.FilterGraph,
        supportsFragmentShader = false,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
        maxTextureSize = 16_384,
        features = emptySet(),
      )
  }
}
