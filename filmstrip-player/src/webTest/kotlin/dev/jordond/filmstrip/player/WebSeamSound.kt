@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.HeardSound
import dev.jordond.filmstrip.player.contract.SeamSoundBackend
import dev.jordond.filmstrip.player.contract.SeamSoundContractTest
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * The seam sound contract in the browser, which decodes the file it wrote back through the page's own audio decoder.
 */
internal object WebSeamSound : SeamSoundBackend, ClipParityBackend by WebClipParity {
  override suspend fun soundOf(composition: Composition): HeardSound {
    val response = fetch(WebClipParity.exportOf(composition).url).await<HeardResponse>()
    val decoded =
      OfflineAudioContext(
        1,
        1,
        DECODE_RATE,
      ).decodeAudioData(response.arrayBuffer().await<JsAny>()).await<JsAny>()
    val channel = firstChannelOf(decoded)
    return HeardSound(FloatArray(lengthOf(channel)) { sampleAt(channel, it) }, DECODE_RATE.toInt())
  }

  private const val DECODE_RATE = 48_000f
}

/**
 * The seam sound cells in the browser.
 */
class WebSeamSoundMediaTest : SeamSoundContractTest(WebSeamSound)

private external class OfflineAudioContext(
  channels: Int,
  length: Int,
  sampleRate: Float,
) : JsAny {
  fun decodeAudioData(data: JsAny): Promise<JsAny>
}

private external fun fetch(url: String): Promise<HeardResponse>

private external interface HeardResponse : JsAny {
  fun arrayBuffer(): Promise<JsAny>
}

private fun firstChannelOf(buffer: JsAny): JsAny = js("buffer.getChannelData(0)")

private fun lengthOf(channel: JsAny): Int = js("channel.length")

private fun sampleAt(
  channel: JsAny,
  index: Int,
): Float = js("channel[index]")
