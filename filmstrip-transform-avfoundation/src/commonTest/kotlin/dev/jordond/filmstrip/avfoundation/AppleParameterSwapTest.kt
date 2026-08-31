package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetImageGenerator
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The parameter swap a preview makes mid-playback, driven through real Core Image.
 *
 * A preview and the export it previews share one `AVVideoComposition`, and reassigning that on a
 * playing item stalls the render, so an edit has to reach the frames through the handler's own
 * state instead. What is asserted here is that it does: the pixels a second render of the same time
 * produces differ, with the generator, the video composition and the composition itself all left
 * exactly as they were.
 *
 * `AVAssetImageGenerator` is the readback because it applies a video composition the same way a
 * player does, so the frames it returns are the frames a preview would show.
 *
 * Skipped when the fixtures are absent, as in [AppleExportTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleParameterSwapTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  @Test
  fun `a swap reaches the frames the same video composition renders`() {
    val source = fixture() ?: return

    val av = graded(source, DIM).toAvComposition()
    val chain = assertNotNull(av.chain, "a composition with a video track has a chain")
    val generator = generatorFor(av)

    val dimmed = generator.frameAt(PROBE)
    chain.updateParameters(graded(source, BRIGHT))
    val brightened = generator.frameAt(PROBE)

    assertFramesDiffer(
      dimmed,
      brightened,
      message = "the swapped parameter never reached the render",
    )
  }

  @Test
  fun `a swap keeps the slots the spans already hold`() {
    val source = fixture() ?: return

    val av = graded(source, DIM).toAvComposition()
    val chain = assertNotNull(av.chain, "a composition with a video track has a chain")
    val slots = chain.spans.map { it.start to it.end }

    val swapped = graded(source, BRIGHT)
    chain.updateParameters(swapped)

    assertTrue(
      chain.spans.map { it.start to it.end } == slots,
      "the swap moved a span from $slots to ${chain.spans.map { it.start to it.end }}",
    )
    assertSame(
      swapped.tracks
        .single()
        .clips
        .single()
        .effects,
      chain.spans.single().effects,
      "the span kept the effects it was built with",
    )
  }

  @Test
  fun `a swap that moves the output frame is refused`() {
    val source = fixture() ?: return
    val chain = assertNotNull(graded(source, DIM).toAvComposition().chain)

    assertFailsWith<IllegalArgumentException> {
      chain.updateParameters(graded(source, BRIGHT, output = Size(160, 90)))
    }
  }

  @Test
  fun `a swap that moves the transfer function is refused`() {
    val source = fixture() ?: return
    val chain = assertNotNull(graded(source, DIM).toAvComposition().chain)

    assertFailsWith<IllegalArgumentException> {
      chain.updateParameters(graded(source, BRIGHT, transfer = HdrTransfer.Pq))
    }
  }

  @Test
  fun `a swap that moves the timeline is refused`() {
    val source = fixture() ?: return
    val chain = assertNotNull(graded(source, DIM).toAvComposition().chain)

    assertFailsWith<IllegalArgumentException> {
      chain.updateParameters(graded(source, BRIGHT, clipEnd = 700.milliseconds))
    }
  }

  /**
   * One clip with one grading effect on it, at whatever [scale] the parameter is set to.
   */
  private fun graded(
    source: String,
    scale: Double,
    output: Size = OUTPUT,
    transfer: HdrTransfer? = null,
    clipEnd: Duration = CLIP,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = Duration.ZERO,
            clips = listOf(clip(source, clipEnd, listOf(scaling(scale)))),
          ),
        ),
      compositionGeometry = emptyList(),
      compositionInputSize = SOURCE,
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = output,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      layoutSize = output,
      fit = Fit.Contain,
      fill = Fill.Black,
      duration = clipEnd,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = transfer,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  private fun clip(
    path: String,
    end: Duration,
    effects: List<ResolvedEffect>,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of(path),
      info =
        MediaInfo(
          duration = 2.seconds,
          video =
            VideoTrackInfo(
              codedSize = SOURCE,
              displaySize = SOURCE,
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = 30f,
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
            ),
          audio = null,
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = end,
      effects = effects,
      gain = 1f,
      startsAtKeyFrame = false,
    )

  /**
   * An effect that multiplies every colour channel by [scale].
   *
   * A flat gain rather than one of the built-in effects, so the only thing separating two renders
   * is the number the swap carried in.
   */
  private fun scaling(scale: Double): ResolvedEffect =
    ResolvedEffect(
      specId = SPEC_ID,
      effect =
        PlatformEffect(
          CoreImageEffect { image, _ -> image.scaledBy(scale) },
        ),
    )

  private fun CIImage.scaledBy(scale: Double): CIImage =
    imageByApplyingFilter(
      "CIColorMatrix",
      mapOf(
        "inputRVector" to CIVector.vectorWithX(scale, 0.0, 0.0, 0.0),
        "inputGVector" to CIVector.vectorWithX(0.0, scale, 0.0, 0.0),
        "inputBVector" to CIVector.vectorWithX(0.0, 0.0, scale, 0.0),
      ),
    )

  /**
   * A generator over [av]'s own composition, rendering through [av]'s video composition.
   *
   * Both tolerances are zeroed so a second read of the same time is the same frame of the source,
   * leaving the swap as the only thing that could move a pixel.
   */
  @Suppress("DEPRECATION")
  private fun generatorFor(av: AvComposition): AVAssetImageGenerator =
    AVAssetImageGenerator(asset = av.composition).apply {
      videoComposition = av.videoComposition
      requestedTimeToleranceBefore = Duration.ZERO.toCMTime()
      requestedTimeToleranceAfter = Duration.ZERO.toCMTime()
    }

  @Suppress("DEPRECATION")
  private fun AVAssetImageGenerator.frameAt(time: Duration): TestFrame {
    val image =
      copyCGImageAtTime(time.toCMTime(), actualTime = null, error = null)
        ?: error("no frame came back at $time")

    try {
      val width = CGImageGetWidth(image).toInt()
      val height = CGImageGetHeight(image).toInt()
      val pixels = ByteArray(width * height * CHANNELS)
      val colorSpace = CGColorSpaceCreateDeviceRGB()

      try {
        pixels.usePinned { pinned ->
          val context =
            CGBitmapContextCreate(
              pinned.addressOf(0),
              width.toULong(),
              height.toULong(),
              BITS_PER_CHANNEL.toULong(),
              (width * CHANNELS).toULong(),
              colorSpace,
              CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: error("could not create a bitmap context")
          try {
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
          } finally {
            CGContextRelease(context)
          }
        }
      } finally {
        CGColorSpaceRelease(colorSpace)
      }

      return TestFrame(pixels, Size(width, height))
    } finally {
      CGImageRelease(image)
    }
  }

  private fun fixture(): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$CLIP_NAME"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val CLIP_NAME = "apple_export_a.mp4"
    const val SPEC_ID = "test.channel-gain"

    val SOURCE = Size(640, 360)
    val OUTPUT = Size(320, 180)
    val CLIP = 1.seconds
    val PROBE = 500.milliseconds

    // Far enough apart that no encoder rounding could account for the difference.
    const val DIM = 0.2
    const val BRIGHT = 0.9

    const val CHANNELS = 4
    const val BITS_PER_CHANNEL = 8
  }
}
