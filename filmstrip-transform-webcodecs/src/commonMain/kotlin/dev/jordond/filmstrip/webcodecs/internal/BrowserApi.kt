@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise
import kotlin.js.definedExternally

// The browser globals the exporter touches, declared once for both targets. They are the same
// globals with the same shapes on js and wasmJs, so only the handful of things a target genuinely
// spells differently, building an object literal and copying an array into the JavaScript heap,
// lives behind expect/actual.

/**
 * Raw bytes owned by JavaScript.
 */
internal external interface ArrayBuffer : JsAny {
  val byteLength: Int
}

/**
 * A byte view, which is what mediabunny's buffer source reads.
 */
internal external class Uint8Array : JsAny {
  constructor(length: Int)

  constructor(buffer: ArrayBuffer)

  val length: Int

  fun at(index: Int): Int
}

/**
 * A float view, for uniforms and vertex data.
 */
internal external class Float32Array(
  length: Int,
) : JsAny {
  val length: Int

  fun at(index: Int): Float
}

/**
 * The render target. Nothing is added to the document, so the composite never touches layout.
 */
internal external class OffscreenCanvas(
  width: Int,
  height: Int,
) : JsAny {
  fun getContext(
    contextId: String,
    options: JsAny,
  ): WebGl2?
}

/**
 * The GL entry points the compositor uses. The call sequence lives in [BrowserCompositor]. This
 * is only the binding.
 */
internal external interface WebGl2 : JsAny {
  fun createShader(type: Int): JsAny?

  fun shaderSource(
    shader: JsAny,
    source: String,
  )

  fun compileShader(shader: JsAny)

  fun getShaderParameter(
    shader: JsAny,
    pname: Int,
  ): Boolean

  fun getShaderInfoLog(shader: JsAny): String?

  fun deleteShader(shader: JsAny?)

  fun createProgram(): JsAny?

  fun attachShader(
    program: JsAny,
    shader: JsAny,
  )

  fun linkProgram(program: JsAny)

  fun getProgramParameter(
    program: JsAny,
    pname: Int,
  ): Boolean

  fun getProgramInfoLog(program: JsAny): String?

  fun deleteProgram(program: JsAny?)

  fun useProgram(program: JsAny?)

  fun getAttribLocation(
    program: JsAny,
    name: String,
  ): Int

  fun getUniformLocation(
    program: JsAny,
    name: String,
  ): JsAny?

  fun createBuffer(): JsAny?

  fun bindBuffer(
    target: Int,
    buffer: JsAny?,
  )

  fun bufferData(
    target: Int,
    data: Float32Array,
    usage: Int,
  )

  fun deleteBuffer(buffer: JsAny?)

  fun enableVertexAttribArray(index: Int)

  fun vertexAttribPointer(
    index: Int,
    size: Int,
    type: Int,
    normalized: Boolean,
    stride: Int,
    offset: Int,
  )

  fun createTexture(): JsAny?

  fun bindTexture(
    target: Int,
    texture: JsAny?,
  )

  fun texParameteri(
    target: Int,
    pname: Int,
    param: Int,
  )

  fun texImage2D(
    target: Int,
    level: Int,
    internalformat: Int,
    format: Int,
    type: Int,
    source: JsAny,
  )

  /**
   * The allocating overload, for a texture with no source image, sized outright instead of
   * uploaded into. This is what backs a framebuffer's colour attachment.
   */
  fun texImage2D(
    target: Int,
    level: Int,
    internalformat: Int,
    width: Int,
    height: Int,
    border: Int,
    format: Int,
    type: Int,
    pixels: JsAny?,
  )

  fun deleteTexture(texture: JsAny?)

  fun createFramebuffer(): JsAny?

  fun bindFramebuffer(
    target: Int,
    framebuffer: JsAny?,
  )

  fun framebufferTexture2D(
    target: Int,
    attachment: Int,
    textarget: Int,
    texture: JsAny?,
    level: Int,
  )

  fun checkFramebufferStatus(target: Int): Int

  fun deleteFramebuffer(framebuffer: JsAny?)

  fun pixelStorei(
    pname: Int,
    param: Int,
  )

  fun viewport(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  )

  fun clearColor(
    red: Float,
    green: Float,
    blue: Float,
    alpha: Float,
  )

  fun clear(mask: Int)

  fun enable(cap: Int)

  fun blendFuncSeparate(
    srcRgb: Int,
    dstRgb: Int,
    srcAlpha: Int,
    dstAlpha: Int,
  )

  fun uniformMatrix3fv(
    location: JsAny?,
    transpose: Boolean,
    value: Float32Array,
  )

  fun uniform1f(
    location: JsAny?,
    x: Float,
  )

  fun uniform1i(
    location: JsAny?,
    x: Int,
  )

  fun uniform1fv(
    location: JsAny?,
    value: Float32Array,
  )

  fun uniform2f(
    location: JsAny?,
    x: Float,
    y: Float,
  )

  fun drawArrays(
    mode: Int,
    first: Int,
    count: Int,
  )

  fun getExtension(name: String): LoseContext?
}

/**
 * `WEBGL_lose_context`, the only way to hand a context back before the browser's cap of roughly
 * sixteen live contexts refuses the next one.
 */
internal external interface LoseContext : JsAny {
  fun loseContext()
}

/**
 * A WebCodecs frame. mediabunny hands one out per decoded sample, and it has to be closed
 * separately from the sample it came from.
 */
internal external interface VideoFrame : JsAny {
  fun close()
}

/**
 * A file handed to the page, either as an object URL or as a download.
 */
internal external class Blob(
  parts: JsArray<JsAny>,
  options: JsAny,
) : JsAny

/**
 * Object URL minting. A URL outlives the page's reference to the blob, so whoever mints one owns
 * revoking it.
 */
internal external object URL : JsAny {
  fun createObjectURL(obj: JsAny): String

  fun revokeObjectURL(url: String)
}

/**
 * The anchor a download is triggered through.
 */
internal external interface HtmlAnchor : JsAny {
  var href: String

  var download: String

  fun click()

  fun remove()
}

/**
 * Enough of the document to hang a download anchor off.
 */
internal external interface HtmlDocument : JsAny {
  val body: HtmlBody

  fun createElement(tagName: String): HtmlAnchor
}

/**
 * The document body, which a download anchor is attached to for the length of one click.
 */
internal external interface HtmlBody : JsAny {
  fun appendChild(child: JsAny): JsAny
}

internal external val document: HtmlDocument

/**
 * The wall clock, for naming a generated download.
 */
internal external object Date : JsAny {
  fun now(): Double
}

/**
 * Schedules [handler] on a later task. Used once, to revoke a download's object URL after the click
 * that started it has been dispatched.
 */
internal external fun setTimeout(
  handler: () -> Unit,
  timeout: Int,
): Int

/**
 * WebCodecs' encoder, used here only to answer capability questions. Encoding itself goes through
 * mediabunny, which owns an encoder of its own and applies backpressure through it.
 */
internal external object VideoEncoder : JsAny {
  fun isConfigSupported(config: JsAny): Promise<EncoderSupport>
}

internal external object AudioEncoder : JsAny {
  fun isConfigSupported(config: JsAny): Promise<EncoderSupport>
}

/**
 * The answer to `isConfigSupported`.
 */
internal external interface EncoderSupport : JsAny {
  val supported: Boolean
}

/**
 * What building an audio graph needs, whichever context it is built on.
 *
 * The export renders its graph offline and the preview plays the same graph live, so everything
 * that wires nodes together is written against this rather than against either one.
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
 * A parameter a node exposes for automation, read here only for its current value.
 */
internal external interface AudioParam : JsAny {
  var value: Float
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
