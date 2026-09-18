package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.GainSegment
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.value
import platform.AVFoundation.AVAudioMixInputParameters
import platform.CoreMedia.CMTimeRange
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

  // A pass used to be laid whole and only then measured against the end, so a track handed a
  // composition longer than its own laid run opened another pass and ran the file past it. The
  // plan's duration is deliberately past the laid run here, which is what that loop read.
  @Test
  fun `lays one span per pass of a looping track and stops where the laid list ends`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val laid =
      laidFrom(
        BED_START,
        listOf(1_100.milliseconds, 1_100.milliseconds, 1_100.milliseconds, 800.milliseconds)
          .map { length -> clip(first, Size(640, 360), 200.milliseconds, 200.milliseconds + length) },
      )
    val run = laid.fold(Duration.ZERO) { total, clip -> total + clip.duration }
    val composition = loopingComposition(laid, start = BED_START, duration = BED_START + run + BEYOND_THE_RUN)

    composition.spans.size shouldBe laid.size
    composition.spans.first().start shouldBe BED_START
    composition.spans.zipWithNext().forEach { (first, second) -> first.end shouldBe second.start }
    composition.composition.duration.toDuration() shouldBe BED_START + run
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

  @Test
  fun `builds no audio mix when every clip's curve is unity`() {
    val composition = threeClips() ?: return

    composition.audioMix shouldBe null
  }

  @Test
  fun `writes a flat gain below unity as a ramp across the placement`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val gain = ResolvedGain.constant(0.5f, Duration.ZERO, 500.milliseconds)

    val composition =
      resolved(listOf(clip(first, Size(640, 360), Duration.ZERO, 500.milliseconds, gain)), 500.milliseconds)

    val ramp = composition.soleAudioParameters()?.rampAt(Duration.ZERO) ?: error("expected a volume at time zero")
    ramp.start shouldBe 0.5f
    ramp.end shouldBe 0.5f
    ramp.rangeStart shouldBe Duration.ZERO
    ramp.rangeLength shouldBe 500.milliseconds
  }

  // AVFoundation interpolates from a volume point to whatever point comes next, so a constant
  // written as a bare point slides across its own clip the moment the clip behind it sits at
  // another level. The two levels here are far enough apart for the slide to show at the middle of
  // either span.
  @Test
  fun `holds each constant clip's own level across its own span`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val second = fixture("apple_export_b.mp4") ?: return
    val clips =
      listOf(
        clip(first, Size(640, 360), Duration.ZERO, LEADING),
        clip(
          second,
          Size(480, 270),
          Duration.ZERO,
          TRAILING,
          ResolvedGain.constant(0.4f, Duration.ZERO, TRAILING),
        ),
      )
    val leading = clips[0].gain.constant ?: error("the leading clip should carry a constant")
    val trailing = clips[1].gain.constant ?: error("the trailing clip should carry a constant")

    val composition = resolved(clips, LEADING + TRAILING)
    val parameters = composition.soleAudioParameters() ?: error("expected an audio mix")

    listOf(Duration.ZERO, 300.milliseconds, 500.milliseconds).forEach { at ->
      val ramp = parameters.rampAt(at) ?: error("expected a ramp at $at")
      ramp.start shouldBe leading
      ramp.end shouldBe leading
      ramp.rangeStart shouldBe Duration.ZERO
      ramp.rangeLength shouldBe LEADING
    }

    listOf(LEADING, 1_000.milliseconds, 1_400.milliseconds).forEach { at ->
      val ramp = parameters.rampAt(at) ?: error("expected a ramp at $at")
      ramp.start shouldBe trailing
      ramp.end shouldBe trailing
      ramp.rangeStart shouldBe LEADING
      ramp.rangeLength shouldBe TRAILING
    }
  }

  // The mapping from a curve's own time onto the composition timeline has to add the clip's
  // placement, not just carry the segment's own times across. The ramping clip sits second, behind
  // a 300ms clip, so a formula that dropped the placement, or used it in place of the segment's own
  // time, lands on the wrong instant here even though both agree at a clip's own start.
  @Test
  fun `ramps a clip's gain across the audio mix at the breakpoints its own curve holds`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val second = fixture("apple_export_b.mp4") ?: return
    val ramp =
      ResolvedGain(
        listOf(
          GainSegment(Duration.ZERO, 400.milliseconds, 0.2f, 0.8f),
          GainSegment(400.milliseconds, 1_000.milliseconds, 0.8f, 0.3f),
        ),
      )

    val composition =
      resolved(
        listOf(
          clip(first, Size(640, 360), Duration.ZERO, 300.milliseconds),
          clip(second, Size(480, 270), Duration.ZERO, 1_000.milliseconds, ramp),
        ),
        1_300.milliseconds,
      )
    val parameters = composition.soleAudioParameters() ?: error("expected an audio mix")

    val firstLeg = parameters.rampAt(500.milliseconds) ?: error("expected a ramp at 500ms")
    firstLeg.start shouldBe 0.2f
    firstLeg.end shouldBe 0.8f
    firstLeg.rangeStart shouldBe 300.milliseconds
    firstLeg.rangeLength shouldBe 400.milliseconds

    val secondLeg = parameters.rampAt(1_000.milliseconds) ?: error("expected a ramp at 1000ms")
    secondLeg.start shouldBe 0.8f
    secondLeg.end shouldBe 0.3f
    secondLeg.rangeStart shouldBe 700.milliseconds
    secondLeg.rangeLength shouldBe 600.milliseconds
  }

  // Two envelope points pinned at the same clip time fold into a zero-length segment, which is a
  // step rather than a ramp, since AVFoundation has no ramp to hold across no time.
  @Test
  fun `writes a zero-length segment as a step rather than a ramp`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val step =
      ResolvedGain(
        listOf(
          GainSegment(Duration.ZERO, 400.milliseconds, 1f, 1f),
          GainSegment(400.milliseconds, 400.milliseconds, 1f, 0f),
          GainSegment(400.milliseconds, 1_000.milliseconds, 0f, 0f),
        ),
      )

    val composition =
      resolved(listOf(clip(first, Size(640, 360), Duration.ZERO, 1_000.milliseconds, step)), 1_000.milliseconds)
    val parameters = composition.soleAudioParameters() ?: error("expected an audio mix")

    parameters.rampAt(200.milliseconds)?.start shouldBe 1f
    val afterStep = parameters.rampAt(700.milliseconds) ?: error("expected a volume after the step")
    afterStep.start shouldBe 0f
    afterStep.end shouldBe 0f
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
        layoutSize = OUTPUT,
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

  // A still holds no track of any type, so an empty range would leave an image-only composition
  // with no duration at all and nothing able to open it.
  @Test
  fun `gives an image clip a real slot on the timeline`() {
    val composition = resolved(listOf(imageClip(PHOTO)), PHOTO)

    composition.composition.duration.toDuration() shouldBe PHOTO
    composition.spans.single().still shouldBe PHOTO_IMAGE
    composition.spans.single().start shouldBe Duration.ZERO
  }

  // A trailing empty range is discarded, so a still last in the sequence used to lose its time off
  // the end of the timeline.
  @Test
  fun `keeps an image clip's slot when it is last`() {
    val first = fixture("apple_export_a.mp4") ?: return

    val composition =
      resolved(
        clips = listOf(clip(first, Size(640, 360), Duration.ZERO, 1.seconds), imageClip(PHOTO)),
        duration = 1.seconds + PHOTO,
      )

    composition.composition.duration.toDuration() shouldBe 1.seconds + PHOTO
    composition.spans.size shouldBe 2
    composition.spans.first().still shouldBe null
    composition.spans.last().still shouldBe PHOTO_IMAGE
    composition.spans.last().start shouldBe 1.seconds
  }

  private fun imageClip(duration: Duration): ResolvedClip =
    ResolvedClip(
      source = MediaSource.Image(PHOTO_IMAGE, duration),
      info = imageMediaInfoOf(Size(640, 360), EXIF_ORIENTATION_NORMAL, "png", duration),
      start = Duration.ZERO,
      end = duration,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, duration),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, duration),
      sourceIndex = 0,
    )

  // A swap rebuilds the spans over the slots they already hold, and a still dropped on the way
  // would leave a preview drawing the seed's own frame from the next parameter change on.
  @Test
  fun `keeps an image clip's still across a parameter swap`() {
    val clips = listOf(imageClip(PHOTO))
    val composition = resolved(clips, PHOTO)

    composition.chain?.updateParameters(resolvedComposition(clips, PHOTO))

    composition.spans.single().still shouldBe PHOTO_IMAGE
  }

  private fun resolved(
    clips: List<ResolvedClip>,
    duration: Duration,
  ): AvComposition = resolvedComposition(clips, duration).toAvComposition()

  /**
   * These clips carrying the spans a planner would have derived for them, laid end to end from
   * [from]. The lowering reads the span rather than working an offset out again, so a fixture has
   * to hold the same slots a plan would.
   */
  private fun laidFrom(
    from: Duration,
    clips: List<ResolvedClip>,
  ): List<ResolvedClip> {
    var at = from
    return clips.map { clip -> clip.laidAt(at).also { at += clip.duration } }
  }

  private fun ResolvedClip.laidAt(at: Duration): ResolvedClip =
    ResolvedClip(
      source = source,
      info = info,
      start = start,
      end = end,
      effects = effects,
      gain = gain,
      startsAtKeyFrame = startsAtKeyFrame,
      span = TimeRange.of(at, at + duration),
      sourceIndex = sourceIndex,
    )

  /**
   * A looping track already laid out as the planner lays one, offset by [start].
   */
  private fun loopingComposition(
    clips: List<ResolvedClip>,
    start: Duration,
    duration: Duration,
  ): AvComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = true,
            start = start,
            clips = clips,
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
      layoutSize = OUTPUT,
      fit = Fit.Contain,
      fill = Fill.Black,
      duration = duration,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    ).toAvComposition()

  private fun resolvedComposition(
    clips: List<ResolvedClip>,
    duration: Duration,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = Duration.ZERO,
            clips = laidFrom(Duration.ZERO, clips),
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
      layoutSize = OUTPUT,
      fit = Fit.Contain,
      fill = Fill.Black,
      duration = duration,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

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
              laidFrom(
                Duration.ZERO,
                listOf(
                  clip(first, Size(640, 360), Duration.ZERO, 700.milliseconds),
                  clip(second, Size(480, 270), 250.milliseconds, 1_100.milliseconds),
                  clip(first, Size(640, 360), 300.milliseconds, 1_333.milliseconds),
                ),
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
      layoutSize = OUTPUT,
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
    gain: ResolvedGain = ResolvedGain.constant(1f, Duration.ZERO, end - start),
    sourceIndex: Int = 0,
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
      gain = gain,
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, end - start),
      sourceIndex = sourceIndex,
    )

  private fun fixture(name: String): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$name"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    val OUTPUT = Size(320, 180)
    val PHOTO = 2.seconds

    // Never opened. The lowering only asks whether the clip is a still, not what the still holds.
    val PHOTO_IMAGE = ImageSource.of("/filmstrip/does-not-exist.png")
    val COMPOSITION_INPUT = Size(640, 360)
    val PROBE_STEP = 37.milliseconds

    // A track offset that divides neither the pass length nor the run, so a span counted from the
    // wrong place lands somewhere the tiling assertion can see.
    val BED_START = 700.milliseconds

    // How far the composition runs past the track's own laid run, which is the room a pass laid
    // whole and only then measured would have overrun into.
    val BEYOND_THE_RUN = 900.milliseconds

    // Two spans of unequal length, both whole numbers of ticks at the backend's timescale, so a
    // ramp read back off AVFoundation matches what was written rather than a rounding of it.
    val LEADING = 600.milliseconds
    val TRAILING = 900.milliseconds
  }
}

/**
 * The one track's input parameters, when this composition carries exactly one.
 */
private fun AvComposition.soleAudioParameters(): AVAudioMixInputParameters? =
  audioMix?.inputParameters?.filterIsInstance<AVAudioMixInputParameters>()?.singleOrNull()

/**
 * The volume ramp AVFoundation holds at [time] for this track, or null where none was written.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVAudioMixInputParameters.rampAt(time: Duration): VolumeRamp? =
  memScoped {
    val start = alloc<FloatVar>()
    val end = alloc<FloatVar>()
    val range = alloc<CMTimeRange>()
    val found =
      getVolumeRampForTime(time.toCMTime(), startVolume = start.ptr, endVolume = end.ptr, timeRange = range.ptr)
    if (!found) return@memScoped null
    VolumeRamp(start.value, end.value, range.start.readValue().toDuration(), range.duration.readValue().toDuration())
  }

private class VolumeRamp(
  val start: Float,
  val end: Float,
  val rangeStart: Duration,
  val rangeLength: Duration,
)
