package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.AppleLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.MEDIA_TICK
import dev.jordond.filmstrip.avfoundation.internal.respannedOnto
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
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.internal.outputSlotsOf
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.LoopingCases
import dev.jordond.filmstrip.transform.internal.GainSegment
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.passesCovering
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVAudioMixInputParameters
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.segments
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
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

  // The third clip opens at 1 550 ms, exactly half way between two slots at 30 fps, so it is the one a clip laid at
  // its exact time would open between two frames.
  @Test
  fun `opens each clip's video on the slot the frame grid names`() {
    val resolved = threeClipsResolved() ?: return
    val laid = resolved.tracks.single().clips
    val composition = resolved.toAvComposition()

    val opens = laid.map { gridTime(it.span.start, FRAME_RATE).asLaid() }
    composition.spans.map { it.start } shouldBe opens
    composition.spans[2].start shouldNotBe laid[2].span.start
    val video = composition.segmentsOf(AVMediaTypeVideo)
    video.map { it.start } shouldBe opens
    video.last().end shouldBe gridTime(laid.last().span.end(), FRAME_RATE).asLaid()
  }

  // The same clips with the last one turned down, so its level is written as a ramp. Only the picture moves onto the
  // grid, so the ramp still covers the span the plan gave the clip rather than the slots its video landed on.
  @Test
  fun `leaves each audio placement where the plan laid it`() {
    val resolved = threeClipsResolved(trailingGain = 0.5f) ?: return
    val trailing =
      resolved.tracks
        .single()
        .clips
        .last()

    val parameters = resolved.toAvComposition().soleAudioParameters() ?: error("expected an audio mix")
    val ramp =
      parameters.rampAt(trailing.span.start + trailing.duration / 2) ?: error("expected a ramp in the last clip")
    ramp.rangeStart shouldBe trailing.span.start.asLaid()
    ramp.rangeLength shouldBe trailing.duration.asLaid()
  }

  // A 1.7 s pass is 42.5 slots at 25 fps, so every other seam of the bed sits half way between two slots, and the run
  // stops half way through one too.
  @Test
  fun `lays every pass of a looping track onto the frame grid end to end`() {
    val long = fixture("apple_export_long.mp4") ?: return
    val trim = LoopingCases.BED_TRIM
    val laid =
      laidFrom(
        Duration.ZERO,
        passesCovering(LoopingCases.BED_LENGTHS, LoopingCases.PRIMARY_RUN).map { pass ->
          clip(long, Size(1280, 720), trim.start, trim.start + pass.length)
        },
      )
    val composition = loopingComposition(laid, Duration.ZERO, LoopingCases.PRIMARY_RUN, GRID_RATE)

    val opens = laid.map { gridTime(it.span.start, GRID_RATE).asLaid() }
    composition.spans.map { it.start } shouldBe opens
    composition.spans.zipWithNext().forEach { (first, second) -> first.end shouldBe second.start }
    assertTrue(
      composition.spans.last().end >= LoopingCases.PRIMARY_RUN,
      "the last span ends at ${composition.spans.last().end}, short of the ${LoopingCases.PRIMARY_RUN} run",
    )
    val video = composition.segmentsOf(AVMediaTypeVideo)
    video.map { it.start } shouldBe opens
    video.last().end shouldBe gridTime(LoopingCases.PRIMARY_RUN, GRID_RATE).asLaid()
  }

  // The middle clip runs from 1.000 to 1.010 s, and at 25 fps both of its ends land on slot 25, so it covers no slot
  // and has no frame of its own to show. Its sound still plays.
  @Test
  fun `lays no video for a clip whose ends land on the same slot`() {
    val resolved = sliverBetweenTwoClips() ?: return
    val laid = resolved.tracks.single().clips
    outputSlotsOf(laid[1].span, GRID_RATE).isEmpty() shouldBe true

    val composition = resolved.toAvComposition()

    val video = composition.segmentsOf(AVMediaTypeVideo)
    video.map { it.start } shouldBe listOf(laid[0], laid[2]).map { gridTime(it.span.start, GRID_RATE).asLaid() }
    video.zipWithNext().forEach { (first, second) -> first.end shouldBe second.start }
    composition.segmentsOf(AVMediaTypeAudio).map { it.start } shouldBe laid.map { it.span.start.asLaid() }
    composition.spans.map { it.attributes.inputSize } shouldBe
      listOf(laid[0], laid[2]).map { it.info.video?.displaySize }
  }

  // The clip covering no slot has no span, so a swap that paired spans with clips by position alone would hand the
  // last span the middle clip's frame, or refuse the swap for holding one span fewer than there are clips.
  @Test
  fun `keeps each span on its own clip across a swap when a clip lays no video`() {
    val resolved = sliverBetweenTwoClips() ?: return
    val laid = resolved.tracks.single().clips
    val composition = resolved.toAvComposition()

    composition.chain?.updateParameters(resolved)

    composition.spans.map { it.attributes.inputSize } shouldBe
      listOf(laid[0], laid[2]).map { it.info.video?.displaySize }
  }

  // A swap finds each span's clip by the slots it covers, so a plan naming no frame rate cannot be swapped in. It fails
  // the way laying that plan down fails rather than dropping every span.
  @Test
  fun `refuses a swap onto a plan with no frame rate the way laying it refuses`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val clips = listOf(clip(first, Size(640, 360), Duration.ZERO, 1.seconds))
    val spans = resolvedComposition(clips, 1.seconds).toAvComposition().spans

    listOf(null, 0).forEach { rate ->
      val unrated = resolvedComposition(clips, 1.seconds, frameRate = rate)
      val laying = assertFailsWith<AppleLoweringFailure> { unrated.toAvComposition() }
      val swapping = assertFailsWith<AppleLoweringFailure> { spans.respannedOnto(unrated) }
      swapping.reason shouldBe laying.reason
    }
  }

  // An untrimmed clip runs to the asset's duration, which here outlasts the picture by 30 ms. 2.03 s is 50.75 slots at
  // 25 fps, so the first pass rounds up past the source's last frame and the second still opens on its own slot.
  @Test
  fun `keeps each pass on its slot when the pass runs past its source's picture`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val run = OUTLASTING * 2
    val laid =
      laidFrom(
        Duration.ZERO,
        passesCovering(listOf(OUTLASTING), run).map { pass ->
          clip(first, Size(640, 360), Duration.ZERO, pass.length, mediaDuration = OUTLASTING)
        },
      )
    val composition = loopingComposition(laid, Duration.ZERO, run, GRID_RATE)

    composition.spans.map { it.start } shouldBe laid.map { gridTime(it.span.start, GRID_RATE).asLaid() }
    composition.spans.zipWithNext().forEach { (first, second) -> first.end shouldBe second.start }
    composition.composition.duration.toDuration() shouldBe gridTime(run, GRID_RATE).asLaid()
  }

  // The first clip reads 30 ms past the two seconds of picture its source holds, and its slots reach 10 ms further.
  // The last opens a single tick before the picture ends. Each stops reading where the picture does and its last frame
  // is stretched over the rest of its slots. The middle clip ends well inside its picture and is laid as it reads, one
  // segment playing as long as it fills.
  @Test
  fun `stretches the last frame only for a clip that runs past its source's picture`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val picture = videoEndOf(first)
    val lastTick = picture - MEDIA_TICK
    val resolved =
      resolvedComposition(
        clips =
          listOf(
            clip(first, Size(640, 360), Duration.ZERO, OUTLASTING, mediaDuration = OUTLASTING),
            clip(first, Size(640, 360), Duration.ZERO, 1.seconds),
            clip(first, Size(640, 360), lastTick, lastTick + 1.seconds),
          ),
        duration = OUTLASTING + 2.seconds,
        frameRate = GRID_RATE,
      )
    val laid = resolved.tracks.single().clips

    val video = resolved.toAvComposition().segmentsOf(AVMediaTypeVideo)

    val (overrunning, fitting, lastTickOnly) =
      laid.map { clip ->
        val opens = gridTime(clip.span.start, GRID_RATE).asLaid()
        val closes = gridTime(clip.span.end(), GRID_RATE).asLaid()
        video.filter { it.start >= opens && it.start < closes } to closes
      }
    listOf(overrunning, lastTickOnly).forEach { (segments, closes) ->
      segments.none { it.empty } shouldBe true
      segments.last().end shouldBe closes
      segments.forEach { segment ->
        assertTrue(segment.sourceEnd <= picture, "a segment reads to ${segment.sourceEnd}, past the $picture picture")
      }
      segments.last().sourceEnd shouldBe picture
    }
    val plain = fitting.first.single()
    (plain.sourceEnd - plain.sourceStart) shouldBe (plain.end - plain.start)
  }

  // 16 ms is 0.48 of a slot at 30 fps and 17 ms is 0.51, so one start rounds down and the other up. The picture opens
  // on the slot the start rounds to with empty time in front of it only when that slot is not the first, and the
  // sound opens on the start itself.
  @Test
  fun `opens a track's video on the slot its start lands on`() {
    val first = fixture("apple_export_a.mp4") ?: return

    listOf(16.milliseconds, 17.milliseconds).forEach { start ->
      val laid = laidFrom(start, listOf(clip(first, Size(640, 360), Duration.ZERO, 1.seconds)))
      val composition = loopingComposition(laid, start, start + 1.seconds)

      val opening = gridTime(start, FRAME_RATE)
      val video = composition.segmentsOf(AVMediaTypeVideo)
      video.map { Triple(it.start, it.end, it.empty) } shouldBe
        listOfNotNull(
          Triple(Duration.ZERO, opening.asLaid(), true).takeIf { opening > Duration.ZERO },
          Triple(opening.asLaid(), gridTime(laid.single().span.end(), FRAME_RATE).asLaid(), false),
        )
      val audio = composition.segmentsOf(AVMediaTypeAudio)
      audio.first { !it.empty }.start shouldBe start.asLaid()
    }
  }

  // 32 does not divide the timescale, so an odd slot lands half a tick late. The still and the last clip each open on
  // an odd slot and close on an even one, where a length rounded on its own would run a tick into the next clip. The
  // still is cut from the seed the same way. Each span opens on the tick its segment does, since a frame at a seam is
  // matched to its clip by the span it falls in.
  @Test
  fun `meets every clip on one tick at a rate the timescale does not divide`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val resolved =
      resolvedComposition(
        clips =
          listOf(
            clip(first, Size(640, 360), Duration.ZERO, UNEVEN_LONG),
            imageClip(UNEVEN_SHORT),
            clip(first, Size(640, 360), Duration.ZERO, UNEVEN_LONG),
            clip(first, Size(640, 360), Duration.ZERO, UNEVEN_SHORT),
          ),
        duration = (UNEVEN_LONG + UNEVEN_SHORT) * 2,
        frameRate = UNEVEN_RATE,
      )
    val laid = resolved.tracks.single().clips

    val composition = resolved.toAvComposition()
    val video = composition.segmentsOf(AVMediaTypeVideo)

    video.map { it.start } shouldBe laid.map { gridTime(it.span.start, UNEVEN_RATE).asLaid() }
    video.zipWithNext().forEach { (first, second) -> first.end shouldBe second.start }
    video.last().end shouldBe gridTime(laid.last().span.end(), UNEVEN_RATE).asLaid()
    composition.spans.map { it.start } shouldBe video.map { it.start }
  }

  // Both clips are cut off the grid at 48 fps and their seams land on odd slots, which only a timescale 48 divides
  // can name exactly. Every span and every segment opens on its frame's own time rather than on a tick beside it.
  @Test
  fun `opens every clip on its frame's own time at 48 fps`() {
    val first = fixture("apple_export_a.mp4") ?: return
    val resolved =
      resolvedComposition(
        clips =
          listOf(
            clip(first, Size(640, 360), Duration.ZERO, 1_030.milliseconds),
            clip(first, Size(640, 360), Duration.ZERO, 1.seconds),
          ),
        duration = 2_030.milliseconds,
        frameRate = WHOLE_TICK_RATE,
      )
    val laid = resolved.tracks.single().clips

    val composition = resolved.toAvComposition()
    val video = composition.segmentsOf(AVMediaTypeVideo)

    val frames = laid.map { frameTimeOf(outputSlotOf(it.span.start, WHOLE_TICK_RATE), WHOLE_TICK_RATE) }
    listOf(composition.spans.map { it.start }, video.map { it.start }).forEach { opens ->
      opens.size shouldBe frames.size
      opens.zip(frames).forEach { (opened, frame) ->
        assertTrue((opened - frame).absoluteValue <= ON_THE_FRAME, "a clip opens at $opened, off its frame at $frame")
      }
    }
    val closes = frameTimeOf(outputSlotOf(laid.last().span.end(), WHOLE_TICK_RATE), WHOLE_TICK_RATE)
    assertTrue((video.last().end - closes).absoluteValue <= ON_THE_FRAME, "the video ends at ${video.last().end}")
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
   * Two one second clips with a 10 ms one between them, at [GRID_RATE], so the middle one covers no slot. It reads its
   * own size, so a span built from it is told apart from one built from either neighbour.
   */
  private fun sliverBetweenTwoClips(): ResolvedComposition? {
    val first = fixture("apple_export_a.mp4") ?: return null
    val second = fixture("apple_export_b.mp4") ?: return null
    val third = fixture("apple_export_portrait.mp4") ?: return null

    return resolvedComposition(
      clips =
        listOf(
          clip(first, Size(640, 360), Duration.ZERO, 1.seconds),
          clip(second, Size(480, 270), Duration.ZERO, SLIVER),
          clip(third, Size(360, 640), Duration.ZERO, 1.seconds),
        ),
      duration = 2.seconds + SLIVER,
      frameRate = GRID_RATE,
    )
  }

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
    frameRate: Int = FRAME_RATE,
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
          frameRate = frameRate,
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
    frameRate: Int? = FRAME_RATE,
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
          frameRate = frameRate,
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

  private fun threeClips(): AvComposition? = threeClipsResolved()?.toAvComposition()

  /**
   * Three clips laid end to end at [FRAME_RATE], the last one held at [trailingGain].
   */
  private fun threeClipsResolved(trailingGain: Float = 1f): ResolvedComposition? {
    val first = fixture("apple_export_a.mp4") ?: return null
    val second = fixture("apple_export_b.mp4") ?: return null
    val trailing = 1_033.milliseconds

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
                  clip(
                    first,
                    Size(640, 360),
                    300.milliseconds,
                    300.milliseconds + trailing,
                    ResolvedGain.constant(trailingGain, Duration.ZERO, trailing),
                  ),
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
          frameRate = FRAME_RATE,
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
    )
  }

  private fun clip(
    path: String,
    size: Size,
    start: Duration,
    end: Duration,
    gain: ResolvedGain = ResolvedGain.constant(1f, Duration.ZERO, end - start),
    sourceIndex: Int = 0,
    mediaDuration: Duration = 2.seconds,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of(path),
      info =
        MediaInfo(
          duration = mediaDuration,
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

  /**
   * Where the picture in the file at [path] ends, read off its video track rather than the asset's duration.
   */
  private fun videoEndOf(path: String): Duration {
    val track =
      AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        .tracksWithMediaType(AVMediaTypeVideo)
        .single() as AVAssetTrack
    return CMTimeRangeGetEnd(track.timeRange).toDuration()
  }

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

    // The fixtures' own rate, which every case here encodes at unless it says otherwise.
    const val FRAME_RATE = 30

    // A slot of 40 ms, so a boundary a few milliseconds off a whole slot is plainly off the grid.
    const val GRID_RATE = 25

    // A rate the backend's timescale does not divide, so a slot lands between two ticks.
    const val UNEVEN_RATE = 32

    // Seventeen and fifteen slots at UNEVEN_RATE, so laid long, short, long, short every other seam opens on an odd
    // slot and closes on an even one.
    val UNEVEN_LONG = 531_250.microseconds
    val UNEVEN_SHORT = 468_750.microseconds

    // A rate the timescale divides, so every slot is a whole number of ticks.
    const val WHOLE_TICK_RATE = 48

    // How far a time read back off AVFoundation may sit from the exact frame time, which is its rounding to seconds.
    val ON_THE_FRAME = 1.microseconds

    // A clip that opens on a whole second and whose end lands on the same slot at GRID_RATE, so it covers none.
    val SLIVER = 10.milliseconds

    // How long an untrimmed clip of a two second fixture runs when its audio outlasts its picture.
    val OUTLASTING = 2_030.milliseconds
  }
}

/**
 * Where [time] lands on the output frame grid at [frameRate].
 */
private fun gridTime(
  time: Duration,
  frameRate: Int,
): Duration = outputSlotTime(outputSlotOf(time, frameRate), frameRate)

/**
 * When frame [slot] falls at [frameRate], exactly rather than rounded to a whole microsecond.
 */
private fun frameTimeOf(
  slot: Long,
  frameRate: Int,
): Duration = (slot.toDouble() / frameRate).seconds

/**
 * This time as AVFoundation reads it back, once it has been rounded onto the backend's timescale.
 */
@OptIn(ExperimentalForeignApi::class)
private fun Duration.asLaid(): Duration = toCMTime().toDuration()

/**
 * Where this span ends, which every span the planner lays carries.
 */
private fun TimeRange.end(): Duration = checkNotNull(endExclusive) { "a laid span carries an end" }

/**
 * Where each segment of this composition's one [mediaType] track sits on the timeline, in order.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AvComposition.segmentsOf(mediaType: String?): List<TrackSegment> {
  val track = composition.tracksWithMediaType(mediaType).single() as AVAssetTrack
  return track.segments.filterIsInstance<AVAssetTrackSegment>().map { segment ->
    segment.timeMapping.useContents {
      TrackSegment(
        start = target.start.readValue().toDuration(),
        end = CMTimeRangeGetEnd(target.readValue()).toDuration(),
        sourceStart = source.start.readValue().toDuration(),
        sourceEnd = CMTimeRangeGetEnd(source.readValue()).toDuration(),
        empty = segment.empty,
      )
    }
  }
}

/**
 * One segment of a composition track, read back as the timeline range it fills and the source range it plays.
 */
private class TrackSegment(
  val start: Duration,
  val end: Duration,
  val sourceStart: Duration,
  val sourceEnd: Duration,
  val empty: Boolean,
)

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
