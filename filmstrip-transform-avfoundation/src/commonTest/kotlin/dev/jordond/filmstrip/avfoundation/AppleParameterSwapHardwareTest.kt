package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
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
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetImageGenerator
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
 * A case fails when its fixture is missing, as in [AppleExportHardwareTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleParameterSwapHardwareTest {
  @Test
  fun `a swap reaches the frames the same video composition renders`() =
    runTest {
      val source = fixture(CLIP_NAME)

      val av = graded(source, DIM).toAvComposition()
      val chain = withClue("a composition with a video track has a chain") { av.chain.shouldNotBeNull() }
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
  fun `a swap keeps the slots the spans already hold`() =
    runTest {
      val source = fixture(CLIP_NAME)

      val av = graded(source, DIM).toAvComposition()
      val chain = withClue("a composition with a video track has a chain") { av.chain.shouldNotBeNull() }
      val slots = chain.spans.map { it.start to it.end }

      val swapped = graded(source, BRIGHT)
      chain.updateParameters(swapped)

      chain.spans.map { it.start to it.end } shouldBe slots
      withClue("the span kept the effects it was built with") {
        chain.spans.single().effects shouldBeSameInstanceAs
          swapped.tracks
            .single()
            .clips
            .single()
            .effects
      }
    }

  @Test
  fun `a swap that moves the output frame is refused`() =
    runTest {
      val source = fixture(CLIP_NAME)
      val chain = graded(source, DIM).toAvComposition().chain.shouldNotBeNull()

      val moved = graded(source, BRIGHT, output = Size(160, 90))

      chain.takesParametersOf(moved) shouldBe false
      assertFailsWith<IllegalArgumentException> { chain.updateParameters(moved) }
    }

  @Test
  fun `a swap that moves the frame rate is refused`() =
    runTest {
      val source = fixture(CLIP_NAME)
      val chain = graded(source, DIM).toAvComposition().chain.shouldNotBeNull()
      val moved = graded(source, BRIGHT, frameRate = 24)

      chain.takesParametersOf(moved) shouldBe false
      assertFailsWith<IllegalArgumentException> { chain.updateParameters(moved) }
    }

  @Test
  fun `a swap from a copy plan to a transcode plan reaches the frames`() =
    runTest {
      val source = fixture(CLIP_NAME)

      val av = plainCopy(source, CLIP).toAvComposition()
      val chain = withClue("a composition with a video track has a chain") { av.chain.shouldNotBeNull() }
      val generator = generatorFor(av)
      val transcode = graded(source, BRIGHT, bitrate = PLANNED_BITRATE)

      val copied = generator.frameAt(PROBE)
      chain.takesParametersOf(transcode) shouldBe true
      chain.updateParameters(transcode)

      assertFramesDiffer(copied, generator.frameAt(PROBE), message = "the swapped parameter never reached the render")
      chain.output.bitrate shouldBe PLANNED_BITRATE
    }

  @Test
  fun `a swap keeps a copy lay ending inside its last slot`() =
    runTest {
      val source = fixture(CLIP_NAME)
      val chain = plainCopy(source, ROUNDS_UP).toAvComposition().chain.shouldNotBeNull()

      chain.takesParametersOf(graded(source, BRIGHT, clipEnd = ROUNDS_UP)) shouldBe true
    }

  @Test
  fun `a swap refuses a copy lay running past its last slot in either direction`() =
    runTest {
      val source = fixture(CLIP_NAME)

      val copied = plainCopy(source, ROUNDS_DOWN).toAvComposition().chain.shouldNotBeNull()
      copied.takesParametersOf(graded(source, BRIGHT, clipEnd = ROUNDS_DOWN)) shouldBe false

      val transcoded = graded(source, BRIGHT, clipEnd = ROUNDS_DOWN).toAvComposition().chain.shouldNotBeNull()
      transcoded.takesParametersOf(plainCopy(source, ROUNDS_DOWN)) shouldBe false
    }

  @Test
  fun `a swap refuses a plan that removes the audio the graph laid`() =
    runTest {
      val source = fixture(CLIP_NAME)
      val av = graded(source, DIM).toAvComposition()

      av.takesParametersOf(graded(source, BRIGHT)) shouldBe true
      val silenced = graded(source, BRIGHT, audio = AudioSpec.Remove, audioCodec = AudioCodec.None)
      av.takesParametersOf(silenced) shouldBe false
    }

  @Test
  fun `a swap that moves the transfer function is refused`() =
    runTest {
      val source = fixture(CLIP_NAME)
      val chain = graded(source, DIM).toAvComposition().chain.shouldNotBeNull()

      assertFailsWith<IllegalArgumentException> {
        chain.updateParameters(graded(source, BRIGHT, transfer = HdrTransfer.Pq))
      }
    }

  @Test
  fun `a swap that moves the timeline is refused`() =
    runTest {
      val source = fixture(CLIP_NAME)
      val chain = graded(source, DIM).toAvComposition().chain.shouldNotBeNull()

      assertFailsWith<IllegalArgumentException> {
        chain.updateParameters(graded(source, BRIGHT, clipEnd = 700.milliseconds))
      }
    }

  // The invariant a looping track needs from a swap: one span per laid pass, each rebuilt from the
  // clip at its own index. A pass of the plan is a clip of its own now, so the two lists have to
  // stay the same length and stay in step.
  @Test
  fun `a swap over a looping track respans every pass with that pass's own clip`() =
    runTest {
      val source = fixture(CLIP_NAME)

      val laid = loopedClips(source)
      val chain = loopingComposition(laid).toAvComposition().chain.shouldNotBeNull()
      val swapped = loopingComposition(loopedClips(source, scale = BRIGHT))
      chain.updateParameters(swapped)

      chain.spans.size shouldBe laid.size
      swapped.tracks.single().clips.forEachIndexed { index, clip ->
        withClue("pass $index kept another pass's effects") {
          chain.spans[index].effects shouldBeSameInstanceAs clip.effects
        }
      }
    }

  /**
   * One clip laid down three times, the last pass cut, carrying the spans the planner would have
   * derived for it.
   */
  private fun loopedClips(
    source: String,
    scale: Double = DIM,
  ): List<ResolvedClip> {
    var at = Duration.ZERO
    return listOf(CLIP, CLIP, LOOP_CUT).map { end ->
      clip(source, end, listOf(scaling(scale)), at = at).also { at += end }
    }
  }

  private fun loopingComposition(clips: List<ResolvedClip>): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = true,
            start = Duration.ZERO,
            clips = clips,
          ),
        ),
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
      fill = Fill.Black,
      duration = clips.fold(Duration.ZERO) { total, clip -> total + clip.sourceDuration },
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  /**
   * One clip with one grading effect on it, at whatever [scale] the parameter is set to.
   */
  private fun graded(
    source: String,
    scale: Double,
    output: Size = OUTPUT,
    transfer: HdrTransfer? = null,
    clipEnd: Duration = CLIP,
    bitrate: Bitrate? = null,
    frameRate: Int = 30,
    audio: AudioSpec = AudioSpec.Keep,
    audioCodec: AudioCodec = AudioCodec.Aac,
  ): ResolvedComposition =
    planned(
      source = source,
      effects = listOf(scaling(scale)),
      output = output,
      transfer = transfer,
      clipEnd = clipEnd,
      videoCodec = VideoCodec.H264,
      bitrate = bitrate,
      frameRate = frameRate,
      audio = audio,
      audioCodec = audioCodec,
      path = ExportPath.Transcode,
    )

  /**
   * One clip with no effect on it, as the planner settles a copy of it, in the source's own codec and at no bitrate.
   */
  private fun plainCopy(
    source: String,
    clipEnd: Duration,
  ): ResolvedComposition =
    planned(
      source = source,
      effects = emptyList(),
      clipEnd = clipEnd,
      videoCodec = VideoCodec.Hevc,
      path = ExportPath.Copy,
    )

  private fun planned(
    source: String,
    effects: List<ResolvedEffect>,
    clipEnd: Duration,
    videoCodec: VideoCodec,
    path: ExportPath,
    output: Size = OUTPUT,
    transfer: HdrTransfer? = null,
    bitrate: Bitrate? = null,
    frameRate: Int = 30,
    audio: AudioSpec = AudioSpec.Keep,
    audioCodec: AudioCodec = AudioCodec.Aac,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = Duration.ZERO,
            clips = listOf(clip(source, clipEnd, effects)),
          ),
        ),
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = output,
          videoCodec = videoCodec,
          audioCodec = audioCodec,
          bitrate = bitrate,
          frameRate = frameRate,
          audioFormat = null,
        ),
      layoutSize = output,
      fill = Fill.Black,
      duration = clipEnd,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = transfer,
      audio = audio,
      adjustments = emptyList(),
      path = path,
    )

  private fun clip(
    path: String,
    end: Duration,
    effects: List<ResolvedEffect>,
    sourceIndex: Int = 0,
    at: Duration = Duration.ZERO,
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
              hasAlpha = false,
            ),
          audioTracks = emptyList(),
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = end,
      effects = effects,
      gain = ResolvedGain.constant(1f, Duration.ZERO, end),
      startsAtKeyFrame = false,
      span = TimeRange.of(at, at + end),
      sourceIndex = sourceIndex,
    )

  /**
   * An effect that multiplies every color channel by [scale].
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
      stage = EffectStage.Color,
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

  private companion object {
    const val CLIP_NAME = "apple_export_a.mp4"
    val SPEC_ID = EffectId("test.channel-gain")

    val SOURCE = Size(640, 360)
    val OUTPUT = Size(320, 180)
    val CLIP = 1.seconds
    val PROBE = 500.milliseconds

    // What the run has left for its last pass, which does not divide the clip evenly.
    val LOOP_CUT = 400.milliseconds

    val PLANNED_BITRATE = Bitrate.kbps(750)

    // Lengths at 30 fps. 30.3 slots round to 30, leaving a stretch past the last slot, and 30.6 round to 31.
    val ROUNDS_DOWN = 1010.milliseconds
    val ROUNDS_UP = 1020.milliseconds

    // Far enough apart that no encoder rounding could account for the difference.
    const val DIM = 0.2
    const val BRIGHT = 0.9
  }
}
