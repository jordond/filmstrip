@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise

/**
 * The audio track of an [Input].
 */
internal external interface InputAudioTrack : JsAny {
  /**
   * The track's id in its file, which for an MP4 is the track id in its track header and for Matroska its track number.
   */
  val id: Double

  fun getSampleRate(): Promise<JsNumber>

  /**
   * The track's ISO 639-2 language code, `und` when the file does not say.
   */
  fun getLanguageCode(): Promise<JsString>

  fun getDisposition(): Promise<TrackDisposition>

  fun getNumberOfChannels(): Promise<JsNumber>

  fun getCodec(): Promise<JsString?>

  /**
   * The config an `AudioDecoder` needs to decode this track's packets, or null when the codec is
   * unknown.
   */
  fun getDecoderConfig(): Promise<JsAny?>
}

/**
 * What a track is meant for. For an MP4, [default] is the enabled flag in the track header.
 */
internal external interface TrackDisposition : JsAny {
  val default: Boolean
}

/**
 * Every audio track of this input, in the order its container lists them.
 */
internal suspend fun Input.audioTrackList(): List<InputAudioTrack> {
  val tracks = getAudioTracks().await()
  return List(tracks.length) { checkNotNull(tracks[it]) { "mediabunny listed a missing audio track" } }
}

/**
 * The first video track this input lists, or null when it carries none.
 *
 * The first is the one every other backend reads, whatever disposition a container gives a later one.
 */
internal suspend fun Input.firstVideoTrack(): InputVideoTrack? {
  val tracks = getVideoTracks().await()
  return if (tracks.length == 0) null else tracks[0]
}
