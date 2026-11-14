package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.chain.linearPixels
import dev.jordond.filmstrip.avfoundation.internal.chain.rect
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGRectInset
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A clip's spatial step that hands back a frame larger than the one it was given, drawn through a real chain.
 *
 * A blur reads past the edge of a frame clamped to its extent, which grows it. The step here grows the frame to twice
 * its size with green around it, and the chain cuts a spatial step back to the frame it was handed, so the clip is laid
 * into the output at its own size.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreImageChainSpatialCropTest {
  @OptIn(BetaInteropApi::class)
  @Test
  fun `a clip's spatial step that grows its frame is cut back before the clip is laid in`() =
    runTest {
      val bytes = OverlayPixels.pngOf(PHOTO_SIZE, RED_ARGB)
      bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }.writeToFile(PATH, true)
      try {
        val chain = checkNotNull(resolved().toAvComposition().chain) { "the composition lays no video" }
        val drawn = chain.drawn(chain.current, SOURCE, MIDDLE)

        withClue("the step failed the chain") { chain.failure.shouldBeNull() }
        drawn.rect() shouldBe listOf(0.0, 0.0, OUTPUT.width.toDouble(), OUTPUT.height.toDouble())
        val pixels = drawn.linearPixels(OUTPUT.width, OUTPUT.height)
        // A tenth of the way in, where the clip laid in at twice its size would show the green it grew by.
        val read = pixels.at(OUTPUT.width / 10, OUTPUT.height / 2)
        withClue("the clip near its edge read $read") {
          read[0] shouldBe (1f plusOrMinus RED_DRIFT)
          read[1] shouldBe (0f plusOrMinus RED_DRIFT)
        }
      } finally {
        NSFileManager.defaultManager.removeItemAtPath(PATH, error = null)
      }
    }

  private fun growing(): ResolvedEffect =
    ResolvedEffect(
      specId = EffectId("dev.jordond.filmstrip.test.growing"),
      effect =
        PlatformEffect(
          CoreImageEffect { image, _ ->
            val grown = image.extent.useContents { CGRectInset(this.readValue(), -size.width / 2, -size.height / 2) }
            image.imageByCompositingOverImage(CIImage(color = CIColor.greenColor).imageByCroppingToRect(grown))
          },
        ),
      stage = EffectStage.Spatial,
    )

  private fun resolved(): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = Duration.ZERO,
            clips = listOf(still()),
          ),
        ),
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = OUTPUT,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = FRAME_RATE,
          audioFormat = null,
        ),
      layoutSize = OUTPUT,
      fill = Fill.Black,
      duration = CLIP,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  private fun still(): ResolvedClip =
    ResolvedClip(
      source = MediaSource.Image(ImageSource.of(PATH), CLIP),
      info = imageMediaInfoOf(PHOTO_SIZE, EXIF_ORIENTATION_NORMAL, "png", CLIP),
      start = Duration.ZERO,
      end = CLIP,
      effects = listOf(growing()),
      gain = ResolvedGain.constant(1f, Duration.ZERO, CLIP),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, CLIP),
      sourceIndex = 0,
    )

  private companion object {
    const val FRAME_RATE = 30
    const val RED_ARGB = 0xFFFF0000.toInt()
    const val RED_DRIFT = 2e-3f
    val OUTPUT = Size(64, 36)
    val PHOTO_SIZE = Size(64, 36)
    val CLIP = 1.seconds
    val MIDDLE = 500.milliseconds
    val PATH = NSTemporaryDirectory() + "filmstrip-apple-spatial-crop.png"
    val SOURCE: CIImage =
      CIImage(color = CIColor.blackColor).imageByCroppingToRect(CGRectMake(0.0, 0.0, 64.0, 36.0))
  }
}
