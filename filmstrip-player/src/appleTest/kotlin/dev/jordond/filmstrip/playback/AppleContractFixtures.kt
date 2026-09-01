package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.avFoundationExportEngine
import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.transform.internal.ResolveResult
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The clip every Apple contract suite plays, downloaded by the module's fixture task.
 *
 * Both `macosArm64Test` and `iosSimulatorArm64Test` depend on that task, so the file is there or
 * the build failed before a test ran. Nothing here skips: a suite that returns early on a missing
 * fixture reports green without having asserted anything.
 */
internal fun appleFixtureClip(): String {
  val directory =
    NSProcessInfo.processInfo.environment[FIXTURES] as? String
      ?: fail("$FIXTURES was not set. The Apple test tasks are what provide it.")
  val path = "$directory/$CLIP_NAME"
  if (!NSFileManager.defaultManager.fileExistsAtPath(path)) fail("The fixture $path was not downloaded.")
  return path
}

/**
 * One trimmed clip of the fixture, with [effects] over the whole composition.
 */
internal fun appleFixtureComposition(effects: List<EffectSpec> = emptyList()): EditComposition =
  EditComposition(
    tracks = listOf(Track(listOf(Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
    effects = effects,
  )

/**
 * The frame the export writes at [position], tapped where the encoder takes it.
 *
 * This is the export path and not a second preview: the edit is negotiated again through
 * [avFoundationExportEngine], lowered again by [toAvComposition] into its own composition and its
 * own chain, and pulled through the `AVAssetReaderVideoCompositionOutput` the writer run itself
 * uses, with the pixel format the writer run asks for. Only VideoToolbox is left out, and what the
 * encoder does to a frame is the one thing a preview is documented not to carry.
 *
 * Frames are pulled from the start of the composition rather than by seeking the reader to
 * [position]. The reader renders on the video composition's own frame grid counted from its time
 * range's start, so a range that opens mid-frame would hand back a composition time neither side
 * asked for.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
internal suspend fun appleExportFrame(
  composition: EditComposition,
  position: Duration,
): TestFrame {
  val engine =
    avFoundationExportEngine(
      prober = chainedProber(CONTRACT_COMPONENTS),
      resolvers = CONTRACT_COMPONENTS.effectResolvers,
    )
  val resolved =
    when (val result = engine.resolve(composition, ExportSpec())) {
      is ResolveResult.Refused -> fail("the export refused the fixture: ${result.error.message}")
      is ResolveResult.Resolved -> result.composition
    }

  val av = resolved.toAvComposition()
  val tracks = av.composition.tracksWithMediaType(AVMediaTypeVideo).filterIsInstance<AVAssetTrack>()
  val output =
    AVAssetReaderVideoCompositionOutput(
      videoTracks = tracks,
      videoSettings = mapOf(PIXEL_FORMAT_KEY to kCVPixelFormatType_32BGRA.toInt()),
    ).apply { videoComposition = av.videoComposition }

  val reader = AVAssetReader.assetReaderWithAsset(av.composition, error = null) ?: fail("no reader for the fixture")
  reader.timeRange = CMTimeRangeMake(Duration.ZERO.toCMTime(), resolved.duration.toCMTime())
  reader.addOutput(output)
  if (!reader.startReading()) fail("the export reader refused to start: ${reader.error?.localizedDescription}")

  try {
    while (true) {
      val buffer = output.copyNextSampleBuffer() ?: fail("the export ran out of frames before $position")
      try {
        val at = CMSampleBufferGetPresentationTimeStamp(buffer).toDuration()
        val image = CMSampleBufferGetImageBuffer(buffer)
        if (image != null && at >= position - HALF_FRAME) return image.toTestFrame()
      } finally {
        CFRelease(buffer)
      }
    }
  } finally {
    reader.cancelReading()
  }
}

/**
 * This BGRA pixel buffer as the tightly packed RGBA the comparison helpers take.
 *
 * The row stride is read off the buffer rather than assumed: CoreVideo aligns rows to its own
 * width, so a frame whose width is not a multiple of that alignment carries padding the packed
 * form must not.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.toTestFrame(): TestFrame {
  CVPixelBufferLockBaseAddress(this, READ_ONLY)
  try {
    val width = CVPixelBufferGetWidth(this).toInt()
    val height = CVPixelBufferGetHeight(this).toInt()
    val stride = CVPixelBufferGetBytesPerRow(this).toInt()
    val base = CVPixelBufferGetBaseAddress(this)?.reinterpret<ByteVar>() ?: fail("the frame had no pixels")

    val pixels = ByteArray(width * height * CHANNELS)
    for (row in 0 until height) {
      val source = row * stride
      val target = row * width * CHANNELS
      for (column in 0 until width) {
        val from = source + column * CHANNELS
        val to = target + column * CHANNELS
        pixels[to] = base[from + 2]
        pixels[to + 1] = base[from + 1]
        pixels[to + 2] = base[from]
        pixels[to + 3] = base[from + 3]
      }
    }
    return TestFrame(pixels, Size(width, height))
  } finally {
    CVPixelBufferUnlockBaseAddress(this, READ_ONLY)
  }
}

private const val FIXTURES = "FILMSTRIP_FIXTURES"
private const val CLIP_NAME = "apple_export_a.mp4"
private const val CHANNELS = 4
private const val READ_ONLY = 1uL

// The documented value of kCVPixelBufferPixelFormatTypeKey. The CoreVideo constants are CFStringRef
// and do not bridge into a Kotlin map as NSString keys, so AVFoundation reads the whole dictionary
// as carrying nothing it recognises.
private const val PIXEL_FORMAT_KEY = "PixelFormatType"

/**
 * Long enough to play through and seek inside, and a whole number of frames at the fixture's rate.
 */
internal val CLIP_LENGTH: Duration = 1500.milliseconds

/**
 * The frame the fixture decodes at, which is the frame an export of it writes.
 *
 * Pinned by the module's `FixtureSpec`, and asserted against a real export before anything is
 * measured from it.
 */
internal val FIXTURE_FRAME: Size = Size(640, 360)

/**
 * A preview cap in the middle of the range rather than at either end.
 *
 * A half or a whole agrees with a scale that adds where it should multiply, so a suite that only
 * capped at those would pass while the arithmetic was wrong.
 */
internal const val CAP_FRACTION: Float = 0.6f

/**
 * The height a capped preview of the fixture renders at.
 */
internal val CAP_HEIGHT: Int = (FIXTURE_FRAME.height * CAP_FRACTION).roundToInt()

/**
 * A caption that wraps, and wraps onto a different number of lines at [CAP_HEIGHT] than at
 * [FIXTURE_FRAME].
 *
 * The system font's metrics are not a straight multiple of its point size, so a block laid out at
 * the preview's own width breaks on different words than the export's. The plate is opaque and
 * saturated so the block's footprint dominates the comparison rather than the glyph edges.
 */
internal const val CAPTION: String = "The quick brown fox jumps over the lazy dog while everyone watches"

internal val CAPTION_STYLE: TextStyle =
  TextStyle(fontSize = 0.06f, backgroundColor = 0xFFFF00FF.toInt(), maxWidth = 0.5f)

/**
 * This frame resampled to [target], by averaging the source area each target pixel covers.
 *
 * An export frame is compared against a preview rendered smaller, and the two have to be the same
 * size before any metric will look at them. Area averaging rather than nearest, so the resample is
 * not itself the largest difference between the two.
 */
internal fun TestFrame.scaledTo(target: Size): TestFrame {
  if (target == size) return this

  val out = ByteArray(target.width * target.height * CHANNELS)
  val xScale = size.width.toDouble() / target.width
  val yScale = size.height.toDouble() / target.height

  for (targetY in 0 until target.height) {
    val top = targetY * yScale
    val bottom = (targetY + 1) * yScale
    for (targetX in 0 until target.width) {
      val left = targetX * xScale
      val right = (targetX + 1) * xScale
      val totals = DoubleArray(CHANNELS)
      var covered = 0.0

      for (sourceY in floor(top).toInt() until minOf(ceil(bottom).toInt(), size.height)) {
        val heightWeight = minOf(bottom, (sourceY + 1).toDouble()) - maxOf(top, sourceY.toDouble())
        for (sourceX in floor(left).toInt() until minOf(ceil(right).toInt(), size.width)) {
          val weight = heightWeight * (minOf(right, (sourceX + 1).toDouble()) - maxOf(left, sourceX.toDouble()))
          val source = (sourceY * size.width + sourceX) * CHANNELS
          for (channel in 0 until CHANNELS) {
            totals[channel] += weight * (pixels[source + channel].toInt() and BYTE_MASK)
          }
          covered += weight
        }
      }

      val base = (targetY * target.width + targetX) * CHANNELS
      for (channel in 0 until CHANNELS) {
        out[base + channel] = (totals[channel] / covered).roundToInt().coerceIn(0, BYTE_MASK).toByte()
      }
    }
  }

  return TestFrame(out, target)
}

private const val BYTE_MASK = 0xFF

/**
 * Composition times both suites compare at, each landing exactly on the fixture's 30fps grid.
 */
internal val PROBE_POSITIONS: List<Duration> = listOf(300.milliseconds, 900.milliseconds)

/**
 * How long one frame of the fixture runs for, at the 30fps its `FixtureSpec` pins.
 */
internal val FIXTURE_FRAME_STEP: Duration = 1.seconds / 30

/**
 * How far apart the fixture's sync samples sit, which is the keyframe interval it was encoded at.
 *
 * An upper bound rather than the exact spacing: x264 is free to place one early and does.
 */
internal val FIXTURE_SYNC_INTERVAL: Duration = 1.seconds

/**
 * A composition time well inside a group of pictures rather than near either end of one, and still
 * exactly on the fixture's frame grid.
 *
 * What tells a decode to the requested frame apart from a snap to the nearest sync sample. A
 * position at either end agrees under both, which is what a probe on the grid's edges would miss.
 */
internal val MID_GOP_POSITION: Duration = 700.milliseconds

// Half a frame at 30fps. A frame is accepted from the presentation time that rounds to the one
// asked for, rather than from the first that is not before it.
private val HALF_FRAME: Duration = 1.seconds / 60

/**
 * The components a host that registered the AVFoundation backend would hand a preview.
 *
 * Both sides of the pixel contract lower through this one registry, so a difference between them is
 * a difference in how the graph is built rather than in what was registered.
 */
@OptIn(InternalFilmstripApi::class)
internal val CONTRACT_COMPONENTS: ComponentRegistry =
  ComponentRegistry.Builder().add(BuiltInEffectResolver()).build()
