package dev.jordond.filmstrip.playback.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.jordond.filmstrip.player.PlaybackError

/**
 * Watches the occasions outside filmstrip that take playback away and media3 does not report.
 *
 * Audio focus and a remote transport both reach the engine as `onPlayWhenReadyChanged` reasons,
 * because `CompositionPlayer` runs an `AudioFocusManager` of its own. The output route going away
 * does not: each internal `ExoPlayer` is built to handle it and pauses itself, and
 * `CompositionPlayer` forwards only the end-of-media reason back up, so the composition's own
 * `playWhenReady` never moves. That occasion belongs to the engine, so it is watched here rather
 * than left to the player.
 *
 * The system putting the app down is the occasion this leaves uncovered. It needs a lifecycle
 * observer, and filmstrip registers none.
 *
 * @param context Where the receiver is registered.
 * @param onInterrupted Called on the main thread, which is where a broadcast is delivered. It hops
 *   to the engine's dispatcher itself.
 */
internal class Media3Interruptions(
  private val context: Context,
  onInterrupted: () -> Unit,
) {
  /**
   * What the system's route broadcast lands on.
   *
   * `ACTION_AUDIO_BECOMING_NOISY` is a protected broadcast, so no process but the system may send
   * one, adb shell included. A test that wants this path has to hand the receiver its intent, which
   * is why this is not private.
   */
  val receiver: BroadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onInterrupted()
      }
    }

  init {
    val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      context.registerReceiver(receiver, filter)
    }
  }

  /**
   * Stops watching. Idempotent, and never left to a finalizer.
   */
  fun dispose() {
    runCatching { context.unregisterReceiver(receiver) }
  }
}

/**
 * Whether media3 stopping playback for [reason] is something outside filmstrip taking it away.
 *
 * Another app taking the audio session and a remote transport both arrive here, each as its own
 * reason, and each is one [dev.jordond.filmstrip.player.PlaybackEvent.ExternalPlayWhenReadyChanged].
 * The route reason is named too, though [Media3Interruptions] is what actually catches that occasion
 * today, so a media3 that starts forwarding it as well reports one interruption rather than two.
 *
 * A composition running out is not one of them, however much it looks like a pause from here. It is
 * [dev.jordond.filmstrip.player.PlaybackEvent.Ended], and reporting it as an interruption would have
 * every clip that plays through read as the system having stepped in.
 */
internal fun isInterruption(reason: Int): Boolean =
  when (reason) {
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
    -> true
    else -> false
  }

/**
 * Whether media3 stopping playback for [reason] means the composition reached its end.
 */
internal fun reachedEndOfMedia(reason: Int): Boolean = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM

/**
 * What one media3 failure means to a player.
 *
 * The two arms a preview reaches that an export does not are the effect-pipeline ones, and they
 * carry their code through [PlaybackError.Underlying] rather than being flattened into a decoder
 * failure they are not.
 */
internal fun PlaybackException.toPlaybackError(): PlaybackError = playbackErrorFor(errorCode, message ?: errorCodeName)

/**
 * What media3's [code] means to a player, carrying [reason] through as the description.
 */
internal fun playbackErrorFor(
  code: Int,
  reason: String,
): PlaybackError =
  when (code) {
    in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> {
      PlaybackError.SourceUnreadable(reason)
    }
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    -> {
      PlaybackError.UnsupportedFormat(reason)
    }
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    -> {
      PlaybackError.DecoderUnavailable(reason)
    }
    in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> {
      PlaybackError.SourceNotExportable(reason)
    }
    else -> {
      PlaybackError.Underlying(code, reason)
    }
  }
