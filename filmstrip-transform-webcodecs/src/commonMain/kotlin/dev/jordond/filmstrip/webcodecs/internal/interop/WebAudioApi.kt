@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.definedExternally

/**
 * What building an audio graph needs, whichever context it is built on.
 *
 * The export renders its graph offline and the preview plays the same graph live, and everything
 * that wires nodes together is written against this, so it builds on either.
 */
internal external interface BaseAudioContext : JsAny {
  val destination: AudioDestinationNode

  val sampleRate: Float

  /**
   * The context's own clock, in seconds. It runs on the audio hardware thread, which is what makes
   * it the steadiest clock a page has, and it does not advance while the context is suspended.
   */
  val currentTime: Double

  fun createGain(): GainNode

  fun createBufferSource(): AudioBufferSourceNode

  fun createBuffer(
    numberOfChannels: Int,
    length: Int,
    sampleRate: Float,
  ): AudioBuffer
}

/**
 * Renders audio without playing it, into an [AudioBuffer] a stream copy or the audio pipeline can
 * hand to mediabunny.
 */
internal external class OfflineAudioContext(
  numberOfChannels: Int,
  length: Int,
  sampleRate: Float,
) : BaseAudioContext {
  override val destination: AudioDestinationNode

  override val sampleRate: Float

  override val currentTime: Double

  override fun createGain(): GainNode

  override fun createBufferSource(): AudioBufferSourceNode

  override fun createBuffer(
    numberOfChannels: Int,
    length: Int,
    sampleRate: Float,
  ): AudioBuffer

  fun startRendering(): Promise<AudioBuffer>
}

/**
 * The live audio graph, which plays what it is given and carries the clock the preview runs on.
 *
 * A context starts `suspended` until the page has had a user gesture. [resume] neither resolves nor
 * rejects while the page is not allowed to start, so [state] and the `statechange` event are the
 * only signals worth building on.
 */
internal external class AudioContext : BaseAudioContext {
  override val destination: AudioDestinationNode

  override val sampleRate: Float

  override val currentTime: Double

  override fun createGain(): GainNode

  override fun createBufferSource(): AudioBufferSourceNode

  override fun createBuffer(
    numberOfChannels: Int,
    length: Int,
    sampleRate: Float,
  ): AudioBuffer

  /**
   * `suspended`, `running` or `closed`.
   */
  val state: String

  fun resume(): Promise<JsAny?>

  fun close(): Promise<JsAny?>

  fun addEventListener(
    type: String,
    listener: () -> Unit,
  )

  fun removeEventListener(
    type: String,
    listener: () -> Unit,
  )
}

/**
 * A block of decoded, non-interleaved audio.
 */
internal external interface AudioBuffer : JsAny {
  val sampleRate: Float

  val length: Int

  val duration: Double

  val numberOfChannels: Int

  fun getChannelData(channel: Int): Float32Array

  /**
   * The parameters default to `null` here rather than take an explicit `null` call, because
   * WebIDL coerces a `null` passed for a `long` to `0`, which is not always the spec default.
   * Leaving a default unset is what compiles to an omitted argument.
   */
  fun copyToChannel(
    source: Float32Array,
    channelNumber: Int,
    startInChannel: Int? = definedExternally,
  )

  fun copyFromChannel(
    destination: Float32Array,
    channelNumber: Int,
    startInChannel: Int? = definedExternally,
  )
}

/**
 * A node in a Web Audio graph.
 */
internal external interface AudioNode : JsAny {
  fun connect(destination: AudioNode): AudioNode

  fun disconnect()
}

/**
 * A parameter a node exposes for automation, either written outright or scheduled against the
 * context's own clock.
 */
internal external interface AudioParam : JsAny {
  var value: Float

  /**
   * Holds the parameter at [value] from [atSeconds], which is also the value a ramp scheduled after
   * it starts from.
   */
  fun setValueAtTime(
    value: Float,
    atSeconds: Double,
  ): AudioParam

  /**
   * Moves the parameter in a straight line from whatever the previous event left it at, arriving at
   * [value] at [atSeconds].
   */
  fun linearRampToValueAtTime(
    value: Float,
    atSeconds: Double,
  ): AudioParam
}

/**
 * A node that scales its input by [gain].
 */
internal external interface GainNode : AudioNode {
  val gain: AudioParam
}

/**
 * A node that plays an [AudioBuffer] into the graph.
 */
internal external interface AudioBufferSourceNode : AudioNode {
  var buffer: AudioBuffer?

  var loop: Boolean

  var loopStart: Double

  var loopEnd: Double

  /**
   * The parameters default to `null` here rather than take an explicit `null` call, because
   * WebIDL coerces a `null` passed for a `double` to `0`, which is not the spec default of
   * playing the whole buffer. Leaving a default unset is what compiles to an omitted argument.
   */
  fun start(
    whenSeconds: Double,
    offsetSeconds: Double? = definedExternally,
    durationSeconds: Double? = definedExternally,
  )

  fun stop(whenSeconds: Double? = definedExternally)
}

/**
 * The terminal node a context's graph renders to.
 */
internal external interface AudioDestinationNode : AudioNode
