package dev.jordond.filmstrip.player.internal

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.jordond.filmstrip.player.PlaybackError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * How the media3 engine reads a `playWhenReady` change and a [PlaybackException], taken apart from the player that
 * raises them.
 *
 * Every one of these is a place where media3 says less than filmstrip's contract does, so the
 * mapping is the whole of the behaviour and none of it needs a decoder to check.
 */
class Media3InterruptionsTest {
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

  private companion object {
    const val WHY = "the decoder gave up"
  }
}
