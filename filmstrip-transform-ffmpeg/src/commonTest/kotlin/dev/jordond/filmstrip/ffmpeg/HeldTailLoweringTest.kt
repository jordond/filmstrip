package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPlanner
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.Lowering
import dev.jordond.filmstrip.ffmpeg.internal.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.previewFrames
import dev.jordond.filmstrip.ffmpeg.internal.previewLanding
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.NegotiatedComposition
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The picture held under an audio track that outlasts it, read off the graph and off the pump's command line.
 *
 * Every edit goes through the real planner, so the slots held and the frames written come off the shared
 * [heldTail] and [videoSlots] rather than a count written here.
 */
class HeldTailLoweringTest {
  private val picture = MediaSource.of("/clips/picture.mp4")
  private val bed = MediaSource.of("/clips/bed.m4a")

  private val infos =
    mapOf(
      picture to
        MediaInfo(
          duration = PICTURE_SOURCE,
          video =
            VideoTrackInfo(
              codedSize = FRAME,
              displaySize = FRAME,
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = 30f,
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
            ),
          audio = AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null),
          isExportable = true,
        ),
      bed to MediaInfo(BED_SOURCE, null, AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null), true),
    )

  // The clones follow the whole composited chain and the cut to the frame count follows them, so the video runs to
  // the composition's end on the primary's last frame. The composition's own trim still closes the chain.
  @Test
  fun `clones the last frame over every slot a longer audio track runs past it`() {
    val lowering = lowered(underBed())
    val negotiated = negotiatedOf(lowering)
    val tail = negotiated.heldTail
    assertFalse(tail.isEmpty(), "the bed does not outlast the picture")

    invocationOf(lowering).filterGraph shouldContain
      "tpad=stop_mode=clone:stop=${tail.count()},trim=end_frame=${negotiated.videoSlots}," +
      "trim=end=${secondsOf(negotiated.duration)},setpts=expr=PTS-STARTPTS[vout]"
  }

  // A composition effect holds the fill back to a final overlay that ends with the frames under it. The clones come
  // after that overlay, so what they hold is the finished frame with every effect frozen on it.
  @Test
  fun `holds the frame after the composition effects and the fill they defer`() {
    val lowering = lowered(underBed(effects = listOf(logo())))
    val negotiated = negotiatedOf(lowering)
    val graph = invocationOf(lowering).filterGraph
    val held = graph.indexOf("tpad=stop_mode=clone:stop=${negotiated.heldTail.count()}")

    graph shouldContain "overlay=shortest=1"
    assertTrue(graph.lastIndexOf("[vfx0]") in 0 until held, "the tail is cloned ahead of the composition effect")
    assertTrue(graph.indexOf("overlay=shortest=1") in 0 until held, "the tail is cloned ahead of the fill")
    graph shouldContain
      "tpad=stop_mode=clone:stop=${negotiated.heldTail.count()},trim=end_frame=${negotiated.videoSlots}," +
      "trim=end=${secondsOf(negotiated.duration)}"
  }

  // A bed that ends with the picture leaves nothing to hold, so the chain is the one it always was.
  @Test
  fun `lays no held tail when nothing outlasts the primary`() {
    val lowering = lowered(underBed(bedLength = SHORT_BED))
    val negotiated = negotiatedOf(lowering)
    negotiated.heldTail.isEmpty() shouldBe true

    val invocation = invocationOf(lowering)
    invocation.filterGraph shouldContain "[v0]trim=end=${secondsOf(negotiated.duration)},setpts=expr=PTS-STARTPTS[vout]"
    invocation.filterGraph shouldNotContain "trim=end_frame=${negotiated.videoSlots},trim=end="
    invocation.videoSlots shouldBe negotiated.videoSlots
  }

  // The pump stops on the video's own last slot, which is now the composition's.
  @Test
  fun `counts every slot the composition covers as a frame the video writes`() {
    val lowering = lowered(underBed())

    invocationOf(lowering).videoSlots shouldBe negotiatedOf(lowering).videoSlots
  }

  // The clip has no frame past its own end to seek to, so a scrub into the tail opens it on the last slot it covers,
  // the graph renders that frame alone and the pump holds it from there. The landing and the frame count stay the
  // scrub's own.
  @Test
  fun `opens a scrub into the held tail on the primary's last covered slot`() {
    val lowering = lowered(underBed())
    val negotiated = negotiatedOf(lowering)
    val invocation = invocationOf(lowering)
    val at = insideTail(negotiated)
    val lastCovered = outputSlotTime(negotiated.heldTail.first - 1, GRID_RATE)

    val previewed = previewOf(invocation, at)
    val frames = invocation.previewFrames(at)

    previewed.after("-ss") shouldBe secondsOf(PICTURE_TRIM.start + lastCovered)
    previewed.after("-frames:v") shouldBe "1"
    frames.rendered shouldBe 1L
    frames.held shouldBe negotiated.videoSlots - outputSlotOf(at, GRID_RATE) - 1
    invocation.previewLanding(at) shouldBe outputSlotTime(outputSlotOf(at, GRID_RATE), GRID_RATE)
  }

  // The scrub lands exactly on the primary's last covered slot rather than past it, the edge between a landing the
  // clip still owns and the tail beyond it. The clip still has that frame to seek to, so the graph renders it alone
  // and the pump holds the rest of the tail from there, the same split as a scrub into the tail settles on.
  @Test
  fun `renders one frame and holds the rest for a scrub landing on the primary's last covered slot`() {
    val lowering = lowered(underBed())
    val negotiated = negotiatedOf(lowering)
    val invocation = invocationOf(lowering)
    val at = outputSlotTime(negotiated.heldTail.first - 1, GRID_RATE)

    val previewed = previewOf(invocation, at)
    val frames = invocation.previewFrames(at)

    previewed.after("-ss") shouldBe secondsOf(PICTURE_TRIM.start + at)
    previewed.after("-frames:v") shouldBe "1"
    frames.rendered shouldBe 1L
    frames.held shouldBe negotiated.heldTail.count().toLong()
    checkNotNull(frames.rendered) + frames.held shouldBe negotiated.videoSlots - outputSlotOf(at, GRID_RATE)
  }

  // Short of the tail the clip still has the frame the scrub lands on, so the seek goes where it always did.
  @Test
  fun `seeks a scrub short of the held tail onto the slot it lands on`() {
    val lowering = lowered(underBed())
    val landing = outputSlotTime(outputSlotOf(INSIDE_PICTURE, GRID_RATE), GRID_RATE)

    previewOf(invocationOf(lowering), INSIDE_PICTURE).after("-ss") shouldBe secondsOf(PICTURE_TRIM.start + landing)
  }

  // Played from short of the tail, the graph stops on the primary's last covered slot, since what it writes past there
  // has the composition effects drawn later than the export draws them, and the pump holds that frame over the tail.
  @Test
  fun `renders a scrub short of the held tail up to the primary's end and holds the rest`() {
    val lowering = lowered(underBed())
    val negotiated = negotiatedOf(lowering)
    val invocation = invocationOf(lowering)
    val landing = outputSlotOf(INSIDE_PICTURE, GRID_RATE)

    val frames = invocation.previewFrames(INSIDE_PICTURE)

    previewOf(invocation, INSIDE_PICTURE).after("-frames:v") shouldBe (negotiated.heldTail.first - landing).toString()
    frames.rendered shouldBe negotiated.heldTail.first - landing
    frames.held shouldBe negotiated.heldTail.count().toLong()
    checkNotNull(frames.rendered) + frames.held shouldBe negotiated.videoSlots - landing
  }

  // With nothing outlasting the primary the graph renders every slot to the end, and there is nothing to hold.
  @Test
  fun `holds nothing for a scrub with no held tail`() {
    val lowering = lowered(underBed(bedLength = SHORT_BED))
    val negotiated = negotiatedOf(lowering)
    val landing = outputSlotOf(INSIDE_PICTURE, GRID_RATE)

    val frames = invocationOf(lowering).previewFrames(INSIDE_PICTURE)

    frames.rendered shouldBe negotiated.videoSlots - landing
    frames.held shouldBe 0L
  }

  // An overlay's branch reads the composition time of the frame it is merged onto, so it is carried to the slot the
  // clip opens on rather than to the scrub. The held frame then carries the overlay the export's does.
  @Test
  fun `carries an overlay's branch to the slot a scrub into the held tail opens on`() {
    val lowering = lowered(underBed(clipEffects = listOf(logo())))
    val negotiated = negotiatedOf(lowering)
    val lastCovered = outputSlotTime(negotiated.heldTail.first - 1, GRID_RATE)

    previewOf(invocationOf(lowering), insideTail(negotiated)).after("-itsoffset") shouldBe secondsOf(lastCovered)
  }

  private fun lowered(composition: EditComposition): Lowering =
    FfmpegPlanner(TOOLCHAIN, listOf(BuiltInEffectResolver()))
      .lower(composition, ExportSpec(frameRate = GRID_RATE), DEVICE, infos)

  private fun negotiatedOf(lowering: Lowering): NegotiatedComposition =
    assertNotNull(lowering.export.composition, "the edit was refused: ${lowering.verdict}")

  private fun invocationOf(lowering: Lowering): Invocation =
    assertNotNull(lowering.invocation, "the edit was refused: ${lowering.verdict}")

  private fun previewOf(
    invocation: Invocation,
    at: Duration,
  ): List<String> =
    invocation.previewArguments(
      TOOLCHAIN,
      FfmpegConfig(),
      List(invocation.inputs.size) { "/input$it" },
      emptyList(),
      at,
    )

  // The middle of the tail and a quarter of a slot on, so the scrub lands on a slot it is not sitting on.
  private fun insideTail(negotiated: NegotiatedComposition): Duration {
    val tail = negotiated.heldTail
    return outputSlotTime(tail.first + (tail.last - tail.first) / 2, GRID_RATE) + QUARTER_SLOT
  }

  private fun underBed(
    bedLength: Duration = BED_LENGTH,
    clipEffects: List<EffectSpec> = emptyList(),
    effects: List<EffectSpec> = emptyList(),
  ): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(listOf(Clip(picture, trim = PICTURE_TRIM, effects = clipEffects))),
          Track(
            clips = listOf(Clip(bed, trim = TimeRange.of(Duration.ZERO, bedLength))),
            content = TrackContent.Audio,
          ),
        ),
      effects = effects,
    )

  private fun logo(): ImageOverlay = ImageOverlay(ImageSource.of("/logo.png"), Corner.TopStart)

  private fun List<String>.after(flag: String): String = this[indexOf(flag) + 1]

  private fun secondsOf(time: Duration): String = formatSeconds(time.toDouble(DurationUnit.SECONDS))

  private companion object {
    // A rate the source is not at, whose 40 ms slot a quarter of is a whole 10 ms.
    const val GRID_RATE = 25
    val QUARTER_SLOT = 10.milliseconds

    val FRAME = Size(1920, 1080)

    // A second of picture cut from inside its source, so the seek it opens on is not zero, under a bed of five.
    val PICTURE_SOURCE = 4.seconds
    val PICTURE_TRIM = TimeRange.of(500.milliseconds, 1_500.milliseconds)
    val BED_SOURCE = 10.seconds
    val BED_LENGTH = 5.seconds

    // A bed that ends before the picture does, and a scrub inside the picture that sits off its slot.
    val SHORT_BED = 600.milliseconds
    val INSIDE_PICTURE = 610.milliseconds

    val DEVICE =
      DeviceCapabilities(
        video =
          listOf(
            VideoEncoderCapability(
              codec = VideoCodec.H264,
              encoderName = "libx264",
              maxSize = Size(7680, 4320),
              maxFrameRate = null,
              maxBitrate = null,
              isHardwareAccelerated = false,
              sizeAlignment = 2,
            ),
          ),
        audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 8)),
        supportsHdrEncoding = false,
        concurrentSessionBudget = null,
      )

    val TOOLCHAIN =
      Toolchain(
        ffmpeg = "/usr/bin/ffmpeg",
        ffprobe = "/usr/bin/ffprobe",
        version = FfmpegVersion("ffmpeg version 9.0.1", 9, 0),
        filters = setOf("overlay", "concat", "amix"),
        encoders = setOf("libx264", "aac"),
      )
  }
}
