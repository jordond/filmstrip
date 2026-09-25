package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotsOf
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.LoopingCases
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The slots every backend holds the primary's last frame over, and the frame count the video track closes on, read off
 * compositions the planner laid itself.
 */
class HeldTailTest {
  @Test
  fun `a non-looping audio track that outlasts the primary leaves a held tail`() {
    val laid = negotiate(underBed(clip(duration = 1.seconds), bed = 5.seconds))

    laid.heldTail shouldBe (outputSlotOf(1.seconds, GRID_RATE) until outputSlotOf(5.seconds, GRID_RATE))
    laid.videoSlots shouldBe outputSlotOf(5.seconds, GRID_RATE)
    laid.heldTail.last + 1 shouldBe laid.videoSlots
    assertTrue(outputSlotOf(3.seconds, GRID_RATE) in laid.heldTail, "the middle of the bed is not held")
  }

  // At 25 fps the primary ends a quarter of a slot past slot 25 and the bed a sixth of one past slot 125, so a tail
  // measured from the clip's own length rather than the slot its pass ends on would open or close a slot out.
  @Test
  fun `a held tail opens on the slot the primary's last pass ends on`() {
    val laid = negotiate(underBed(clip(duration = 1_010.milliseconds), bed = 5_006.milliseconds))
    val lastPass = laid.primary.clips.last()

    laid.heldTail.first shouldBe outputSlotsOf(lastPass.span, GRID_RATE).last + 1
    laid.heldTail.last + 1 shouldBe outputSlotOf(5_006.milliseconds, GRID_RATE)
  }

  // Read slot by slot rather than at the two ends, so a tail that skipped or doubled a slot anywhere in its middle
  // shows up here.
  @Test
  fun `the lead the passes and the held tail fill the video track between them`() {
    val primary =
      Track(listOf(clip(duration = 1_010.milliseconds), clip(duration = 770.milliseconds)), start = 300.milliseconds)
    val laid = negotiate(EditComposition(listOf(primary, bed(5_006.milliseconds))))
    val laidPrimary = laid.primary
    val lead = 0L until outputSlotOf(laidPrimary.start, GRID_RATE)
    val owners = listOf(lead) + laidPrimary.clips.map { outputSlotsOf(it.span, GRID_RATE) } + listOf(laid.heldTail)

    (0L until laid.videoSlots).forEach { slot ->
      assertEquals(1, owners.count { slot in it }, "slot $slot is not covered exactly once")
    }
    assertTrue(outputSlotOf(3.seconds, GRID_RATE) in laid.heldTail, "the middle of the bed is not held")
  }

  @Test
  fun `a late audio track's end sets the tail`() {
    val late = Track(listOf(audioOnlyClip(1.seconds)), content = TrackContent.Audio, start = 4_500.milliseconds)
    val laid = negotiate(EditComposition(listOf(Track(listOf(clip(duration = 1.seconds))), late)))

    laid.heldTail.first shouldBe outputSlotOf(1.seconds, GRID_RATE)
    laid.heldTail.last + 1 shouldBe outputSlotOf(5_500.milliseconds, GRID_RATE)
    laid.videoSlots shouldBe outputSlotOf(5_500.milliseconds, GRID_RATE)
  }

  @Test
  fun `a looping audio track leaves no tail`() {
    val looping = Track(listOf(audioOnlyClip(3.seconds)), content = TrackContent.Audio, looping = true)
    val laid = negotiate(EditComposition(listOf(Track(listOf(clip(duration = 1.seconds))), looping)))

    assertTrue(laid.heldTail.isEmpty(), "a looping bed left ${laid.heldTail}")
    laid.videoSlots shouldBe outputSlotOf(1.seconds, GRID_RATE)
  }

  // Case three of the looping suite: the primary repeats under a longer audio-only track, so its last pass is cut
  // on the composition's end.
  @Test
  fun `a looping primary leaves no tail`() {
    val primary = Track(listOf(clip(duration = 3.seconds, trim = LoopingCases.VIDEO_TRIM)), looping = true)
    val underlay = bed(LoopingCases.UNDERLAY_RUN)
    val laid = negotiate(EditComposition(listOf(primary, underlay)))

    assertTrue(laid.heldTail.isEmpty(), "a looping primary left ${laid.heldTail}")
    laid.videoSlots shouldBe outputSlotOf(LoopingCases.UNDERLAY_RUN, GRID_RATE)
    laid.videoSlots shouldBe slotsCoveredBy(laid.primary)
  }

  @Test
  fun `a copy plan leaves no tail`() {
    val laid = negotiate(EditComposition(listOf(Track(listOf(clip())))), ExportSpec())
    val frameRate = assertNotNull(laid.output.frameRate)

    laid.path shouldBe ExportPath.Transmux
    assertTrue(laid.heldTail.isEmpty(), "a copy left ${laid.heldTail}")
    laid.videoSlots shouldBe outputSlotOf(laid.duration, frameRate)
  }

  @Test
  fun `a still primary under a bed leaves a tail`() {
    val laid = negotiate(underBed(imageClip(duration = 1.seconds), bed = 3.seconds))

    laid.heldTail shouldBe (outputSlotOf(1.seconds, GRID_RATE) until outputSlotOf(3.seconds, GRID_RATE))
    assertTrue(outputSlotOf(2.seconds, GRID_RATE) in laid.heldTail, "the middle of the bed is not held")
  }

  @Test
  fun `a late primary keeps its lead apart from its tail`() {
    val primary = Track(listOf(clip(duration = 1.seconds)), start = 500.milliseconds)
    val laid = negotiate(EditComposition(listOf(primary, bed(5.seconds))))
    val covered = slotsCoveredBy(laid.primary)

    laid.heldTail.first shouldBe outputSlotOf(1_500.milliseconds, GRID_RATE)
    laid.videoSlots - covered - laid.heldTail.count() shouldBe outputSlotOf(500.milliseconds, GRID_RATE)
  }

  @Test
  fun `videoSlots equals the lead plus every pass's slots when nothing outlasts the primary`() {
    val primary =
      Track(listOf(clip(duration = 1_010.milliseconds), clip(duration = 770.milliseconds)), start = 300.milliseconds)
    val laid = negotiate(EditComposition(listOf(primary, bed(1.seconds))))

    assertTrue(laid.heldTail.isEmpty(), "a shorter bed left ${laid.heldTail}")
    laid.videoSlots shouldBe outputSlotOf(laid.primary.start, GRID_RATE) + slotsCoveredBy(laid.primary)
  }

  @Test
  fun `a negotiated and a resolved composition read the same tail`() {
    val negotiated = negotiate(underBed(clip(duration = 1_010.milliseconds), bed = 5_006.milliseconds))
    val resolved = negotiated.toResolvedComposition()

    resolved.heldTail shouldBe negotiated.heldTail
    resolved.videoSlots shouldBe negotiated.videoSlots
    negotiated.heldTail shouldBe heldTailOf(negotiated.primary, negotiated.duration, GRID_RATE)
    negotiated.videoSlots shouldBe videoSlotsOf(negotiated.duration, GRID_RATE)
  }

  private fun negotiate(
    composition: EditComposition,
    spec: ExportSpec = ExportSpec(frameRate = GRID_RATE),
  ) = assertNotNull(planner().negotiate(composition, spec, device(), infos(composition)).composition)

  private val NegotiatedComposition.primary: ResolvedTrack
    get() = tracks.first()

  private fun slotsCoveredBy(track: ResolvedTrack): Long =
    track.clips.sumOf { outputSlotsOf(it.span, GRID_RATE).count().toLong() }

  private fun underBed(
    primary: Clip,
    bed: Duration,
  ) = EditComposition(listOf(Track(listOf(primary)), bed(bed)))

  private fun bed(duration: Duration) = Track(listOf(audioOnlyClip(duration)), content = TrackContent.Audio)

  private fun planner() =
    ExportPlanner(
      resolvers = emptyList(),
      renderCapabilities = { size, hdr ->
        RenderCapabilities(
          api = RenderApi.OpenGlEs,
          supportsFragmentShader = true,
          supportsComputeShader = false,
          supportsHdr = hdr,
          colorSpaces = setOf(ColorSpace.Bt709),
          maxTextureSize = maxOf(size.width, size.height),
          features = emptySet(),
        )
      },
      parityOf = { EffectParity.Exact },
      unclaimedMessage = { "nothing claimed $it" },
      ladder = listOf(VideoCodec.H264, VideoCodec.Hevc),
      supportsPassthrough = true,
      canCopy = { true },
    )

  private fun device() =
    DeviceCapabilities(
      video = listOf(encoder(VideoCodec.H264), encoder(VideoCodec.Hevc)),
      audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2)),
      supportsHdrEncoding = false,
      concurrentSessionBudget = null,
    )

  private fun encoder(codec: VideoCodec) =
    VideoEncoderCapability(
      codec = codec,
      encoderName = null,
      maxSize = Size(3840, 2160),
      maxFrameRate = null,
      maxBitrate = null,
      isHardwareAccelerated = true,
      sizeAlignment = 2,
    )

  private fun infos(composition: EditComposition): Map<MediaSource, MediaInfo> =
    composition.tracks
      .flatMap { it.clips }
      .associate { it.source to INFOS.getValue(it.source) }

  private fun clip(
    duration: Duration = 6_000.milliseconds,
    trim: TimeRange? = null,
  ): Clip {
    val size = Size(1920, 1080)
    val source = MediaSource.of("/fixtures/clip-${INFOS.size}.mp4")
    INFOS[source] =
      MediaInfo(
        duration = duration,
        video =
          VideoTrackInfo(
            codedSize = size,
            displaySize = size,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            frameRate = SOURCE_RATE,
            codec = trackCodecOf("avc1"),
            bitDepth = 8,
            colorSpace = ColorSpace.Bt709,
            hdrTransfer = null,
            bitrate = Bitrate.mbps(12),
          ),
        audio = null,
        isExportable = true,
      )
    return Clip(source, trim)
  }

  private fun imageClip(duration: Duration): Clip {
    val source = MediaSource.Image(ImageSource.of("/fixtures/still-${INFOS.size}.jpg"), duration)
    INFOS[source] = imageMediaInfoOf(Size(1920, 1080), EXIF_ORIENTATION_NORMAL, "jpeg", duration)
    return Clip(source)
  }

  private fun audioOnlyClip(duration: Duration): Clip {
    val source = MediaSource.of("/fixtures/audio-${INFOS.size}.m4a")
    INFOS[source] =
      MediaInfo(
        duration = duration,
        video = null,
        audio = AudioTrackInfo(trackCodecOf("mp4a"), 44_100, 2, null),
        isExportable = true,
      )
    return Clip(source)
  }

  private val INFOS = mutableMapOf<MediaSource, MediaInfo>()

  private companion object {
    const val GRID_RATE = 25
    const val SOURCE_RATE = 30f
  }
}
