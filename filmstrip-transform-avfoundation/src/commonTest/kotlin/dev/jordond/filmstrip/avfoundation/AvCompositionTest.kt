package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.duration
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The lowering from a resolved composition onto AVFoundation.
 *
 * Span tiling is the thing worth asserting here. A gap or an overlap between two spans fails the
 * render with `AVErrorInvalidVideoComposition`, which names no clip and no time, so it has to be
 * caught where the spans are built, not where they are used.
 */
@OptIn(ExperimentalForeignApi::class)
class AvCompositionTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  @Test
  fun `tiles the timeline with no gap and no overlap`() {
    val composition = threeClips() ?: return

    val spans = composition.spans
    spans.size shouldBe 3
    spans.first().start shouldBe Duration.ZERO
    spans.zipWithNext().forEach { (first, second) ->
      first.end shouldBe second.start
    }
  }

  @Test
  fun `runs the last span to the composition's own duration`() {
    val composition = threeClips() ?: return

    val whole = composition.composition.duration.toDuration()
    assertTrue(
      composition.spans.last().end >= whole,
      "the last span ends at ${composition.spans.last().end} and the composition runs to $whole",
    )
  }

  // Every clip's effects were resolved against that clip's own frame, so the attributes a span
  // carries have to be that frame, never the output's.
  @Test
  fun `measures each span against its own clip's frame`() {
    val composition = threeClips() ?: return

    composition.spans[0].attributes.inputSize shouldBe Size(640, 360)
    composition.spans[1].attributes.inputSize shouldBe Size(480, 270)
    composition.spans.forEach { it.attributes.outputSize shouldBe OUTPUT }
  }

  @Test
  fun `covers every instant of the timeline with exactly one span`() {
    val composition = threeClips() ?: return

    val whole = composition.composition.duration.toDuration()
    var probe = Duration.ZERO
    while (probe < whole) {
      composition.spans.count { it.covers(probe) } shouldBe 1
      probe += PROBE_STEP
    }
  }

  // Handing the composition-geometry step a frame already pinned to the output leaves a
  // composition-level Crop measuring against the wrong aspect. COMPOSITION_INPUT is deliberately
  // not OUTPUT, so swapping one for the other still fails this.
  @Test
  fun `composition geometry is measured against the frame clip effects left behind rather than the output`() {
    val first = fixture("apple_export_a.mp4") ?: return

    val composition =
      ResolvedComposition(
        tracks =
          listOf(
            ResolvedTrack(
              content = TrackContent.AudioAndVideo,
              looping = false,
              start = Duration.ZERO,
              clips = listOf(clip(first, Size(640, 360), Duration.ZERO, 1.seconds)),
            ),
          ),
        compositionGeometry = emptyList(),
        compositionInputSize = COMPOSITION_INPUT,
        compositionEffects = emptyList(),
        output =
          OutputFormat(
            size = OUTPUT,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.Aac,
            bitrate = null,
            frameRate = 30,
            audioFormat = null,
          ),
        fit = Fit.Contain,
        fill = Fill.Black,
        duration = 1.seconds,
        hdr = ResolvedHdr.Keep,
        hdrTransfer = null,
        audio = AudioSpec.Keep,
        adjustments = emptyList(),
        path = ExportPath.Transcode,
      ).toAvComposition()

    val inputSize = composition.chain?.geometryAttributes?.inputSize
    inputSize shouldBe COMPOSITION_INPUT
    inputSize shouldNotBe OUTPUT
  }

  private fun threeClips(): AvComposition? {
    val first = fixture("apple_export_a.mp4") ?: return null
    val second = fixture("apple_export_b.mp4") ?: return null

    return ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = Duration.ZERO,
            clips =
              listOf(
                clip(first, Size(640, 360), Duration.ZERO, 700.milliseconds),
                clip(second, Size(480, 270), 250.milliseconds, 1_100.milliseconds),
                clip(first, Size(640, 360), 300.milliseconds, 1_333.milliseconds),
              ),
          ),
        ),
      compositionGeometry = emptyList(),
      compositionInputSize = OUTPUT,
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = OUTPUT,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      fit = Fit.Contain,
      fill = Fill.Black,
      duration = 2_583.milliseconds,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    ).toAvComposition()
  }

  private fun clip(
    path: String,
    size: Size,
    start: Duration,
    end: Duration,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of(path),
      info =
        MediaInfo(
          duration = 2.seconds,
          video =
            VideoTrackInfo(
              codedSize = size,
              displaySize = size,
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
      start = start,
      end = end,
      effects = emptyList(),
      gain = 1f,
      startsAtKeyFrame = false,
    )

  private fun fixture(name: String): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$name"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    val OUTPUT = Size(320, 180)
    val COMPOSITION_INPUT = Size(640, 360)
    val PROBE_STEP = 37.milliseconds
  }
}
