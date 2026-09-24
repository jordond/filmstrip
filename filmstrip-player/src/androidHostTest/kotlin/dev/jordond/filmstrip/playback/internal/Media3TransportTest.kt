package dev.jordond.filmstrip.playback.internal

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.SeekAccuracy
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The decisions the media3 engine makes about a transport change, taken apart from the player that
 * raises them.
 *
 * Every one of these is a place where media3 says less than filmstrip's contract does, so the
 * mapping is the whole of the behaviour and none of it needs a decoder to check.
 */
class Media3TransportTest {
  @Test
  fun `a relaxed seek is clamped up to exact, which is all media3 offers`() {
    clampedAccuracy(SeekAccuracy.Nearest) shouldBe SeekAccuracy.Exact
    clampedAccuracy(SeekAccuracy.Exact) shouldBe SeekAccuracy.Exact
  }

  /**
   * Which half of the resize path a change of shape takes.
   *
   * media3 applies the size it reads off the holder itself, and drops it only while it has no graph
   * to hand it to. Handing the output back tears the graph's surface down and blocks the player's
   * thread until it is gone, so it is spent on the window that needs it and nowhere else.
   */
  @Test
  fun `a surface resized before a graph is up is handed back, and one resized after is redrawn`() {
    surfaceResizeAction(hasGraph = false) shouldBe SurfaceResize.Reapply
    surfaceResizeAction(hasGraph = true) shouldBe SurfaceResize.Redraw
  }

  /**
   * The buffer follows the frame the graph renders, and nothing else.
   *
   * A buffer that tracked the view would reallocate as the view moved, and the reallocation a swap
   * causes would land at the moment the swap is revealed, which is the one place it is visible.
   */
  @Test
  fun `the buffer is fixed to a rendered frame that changed, and left alone otherwise`() {
    previewBufferChange(current = null, rendered = Size(1920, 1080)) shouldBe Size(1920, 1080)
    previewBufferChange(current = Size(1920, 1080), rendered = Size(1080, 1920)) shouldBe Size(1080, 1920)
    previewBufferChange(current = Size(1920, 1080), rendered = Size(1920, 1080)) shouldBe null
    previewBufferChange(current = Size(1920, 1080), rendered = Size(0, 0)) shouldBe null
  }

  @Test
  fun `audio focus, a lost route and a remote transport are interruptions`() {
    isInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) shouldBe true
    isInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) shouldBe true
    isInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE) shouldBe true
  }

  // The one that would turn every composition that plays through into an external pause.
  @Test
  fun `reaching the end of the media is never an interruption`() {
    isInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) shouldBe false
    reachedEndOfMedia(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) shouldBe true
  }

  @Test
  fun `the engine's own pause is neither an interruption nor an ending`() {
    isInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) shouldBe false
    reachedEndOfMedia(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) shouldBe false
    isInterruption(Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG) shouldBe false
  }

  @Test
  fun `an io failure anywhere in its range reads as an unreadable source`() {
    playbackErrorFor(
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
      WHY,
    ).shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
    playbackErrorFor(
      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      WHY,
    ).shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
    playbackErrorFor(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, WHY)
      .shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
  }

  @Test
  fun `a drm failure anywhere in its range reads as a source that cannot be exported`() {
    playbackErrorFor(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED, WHY)
      .shouldBeInstanceOf<PlaybackError.SourceNotExportable>()
    playbackErrorFor(PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR, WHY)
      .shouldBeInstanceOf<PlaybackError.SourceNotExportable>()
    playbackErrorFor(PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED, WHY)
      .shouldBeInstanceOf<PlaybackError.SourceNotExportable>()
  }

  @Test
  fun `a decoder that would not start is told apart from a format it would not take`() {
    playbackErrorFor(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, WHY)
      .shouldBeInstanceOf<PlaybackError.DecoderUnavailable>()
    playbackErrorFor(PlaybackException.ERROR_CODE_DECODING_FAILED, WHY)
      .shouldBeInstanceOf<PlaybackError.DecoderUnavailable>()
    playbackErrorFor(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, WHY)
      .shouldBeInstanceOf<PlaybackError.UnsupportedFormat>()
    playbackErrorFor(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, WHY)
      .shouldBeInstanceOf<PlaybackError.UnsupportedFormat>()
  }

  // The two arms a preview reaches that an export does not, which is why they keep their code.
  @Test
  fun `an effect pipeline failure carries media3's own code through`() {
    val init = playbackErrorFor(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED, WHY)
    val processing = playbackErrorFor(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, WHY)

    init.shouldBeInstanceOf<PlaybackError.Underlying>().platformCode shouldBe
      PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED
    processing.shouldBeInstanceOf<PlaybackError.Underlying>().platformCode shouldBe
      PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
  }

  @Test
  fun `an equal edit costs the platform nothing at all`() {
    loadCostFor(CompositionDiff.Equal, hasGraph = true) { false } shouldBe LoadCost.Nothing
  }

  @Test
  fun `a parameter change the standing graph took costs a redraw, and one it refused costs a rebuild`() {
    loadCostFor(CompositionDiff.ParametersOnly, hasGraph = true) { true } shouldBe LoadCost.Parameters
    loadCostFor(CompositionDiff.ParametersOnly, hasGraph = true) { false } shouldBe LoadCost.Rebuild
  }

  @Test
  fun `a structural change is never offered to the standing graph`() {
    var offered = false
    loadCostFor(CompositionDiff.Structural, hasGraph = true) {
      offered = true
      true
    } shouldBe LoadCost.Rebuild
    offered shouldBe false
  }

  @Test
  fun `the first edit rebuilds whatever the diff says, there being no graph to reuse`() {
    loadCostFor(CompositionDiff.Equal, hasGraph = false) { true } shouldBe LoadCost.Rebuild
    loadCostFor(CompositionDiff.ParametersOnly, hasGraph = false) { true } shouldBe LoadCost.Rebuild
  }

  /**
   * A step taken from between two frames lands on a frame, counted from the one the playhead is nearest.
   *
   * media3 reports its playhead in whole milliseconds, which at most rates is never on a frame at all.
   */
  @Test
  fun `a frame step from between two frames lands on the output grid`() {
    val slot = outputSlotOf(BETWEEN_FRAMES, RATE)

    frameStepLanding(BETWEEN_FRAMES, 1, RATE, LENGTH) shouldBe outputSlotTime(slot + 1, RATE)
    frameStepLanding(BETWEEN_FRAMES, -1, RATE, LENGTH) shouldBe outputSlotTime(slot - 1, RATE)
  }

  @Test
  fun `a run of single frame steps lands where one step of the whole count does`() {
    val stepped = (1..STEPS).fold(Duration.ZERO) { at, _ -> frameStepLanding(at, 1, RATE, LENGTH).shouldNotBeNull() }

    stepped shouldBe outputSlotTime(STEPS.toLong(), RATE)
    stepped shouldBe frameStepLanding(Duration.ZERO, STEPS, RATE, LENGTH)
  }

  @Test
  fun `a frame step that would leave the composition is refused`() {
    frameStepLanding(Duration.ZERO, -1, RATE, LENGTH) shouldBe null
    frameStepLanding(LENGTH, 1, RATE, LENGTH) shouldBe null
    frameStepLanding(LENGTH, -1, RATE, LENGTH) shouldBe outputSlotTime(outputSlotOf(LENGTH, RATE) - 1, RATE)
  }

  private companion object {
    const val WHY = "the decoder gave up"

    // A frame at this rate is not a whole number of microseconds, so adding frame lengths drifts off the grid.
    const val RATE = 30
    const val STEPS = 30
    val LENGTH: Duration = 1500.milliseconds
    val BETWEEN_FRAMES: Duration = 717.milliseconds
  }
}
