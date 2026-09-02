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
 * Raw bytes owned by JavaScript. Allocated here for the plane bytes `VideoFrame.copyTo` fills.
 */
internal external class ArrayBuffer(
  length: Int,
) : JsAny {
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
 * A sixteen-bit view over one plane of a ten-bit frame, which is the shape `texImage2D` takes for
 * an `R16UI` upload.
 */
internal external class Uint16Array(
  buffer: ArrayBuffer,
  byteOffset: Int,
  length: Int,
) : JsAny {
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

  /**
   * Which texture unit the next `bindTexture` names. The unpack pass reads three planes at once, so
   * it binds one texture per unit rather than one at a time.
   */
  fun activeTexture(texture: Int)

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

  fun disable(cap: Int)

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

  fun uniformMatrix4fv(
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

  /**
   * Reads the bound framebuffer back into [pixels]. Synchronous, and it stalls the GL pipeline
   * until the draw behind it has finished, which is what makes it the pack pass's whole cost.
   *
   * Rows come back bottom-up.
   */
  fun readPixels(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    format: Int,
    type: Int,
    pixels: Uint8Array,
  )

  fun getExtension(name: String): GlExtension?
}

/**
 * An extension object, handed back by name or null where the context has none.
 *
 * `WEBGL_lose_context` is the only one whose members are called. Everything else is asked for so
 * the answer can be null-checked, which is what a capability probe needs.
 */
internal external interface GlExtension : JsAny {
  fun loseContext()
}

/**
 * A WebCodecs frame. mediabunny hands one out per decoded sample, and it has to be closed
 * separately from the sample it came from.
 *
 * Only the image upload takes one. Everything that reads planes goes through the sample itself,
 * which forwards to the frame underneath it.
 */
internal external interface VideoFrame : JsAny {
  fun close()
}

/**
 * Where one plane sits inside the buffer a `copyTo` filled.
 *
 * The stride is the decoder's own and is wider than the frame on plenty of sources, so it is read
 * rather than computed.
 */
internal external interface PlaneLayout : JsAny {
  val offset: Int

  val stride: Int
}

/**
 * WebCodecs' decoder, used here only to ask whether a config decodes in software. Decoding itself
 * goes through mediabunny.
 */
internal external object VideoDecoder : JsAny {
  fun isConfigSupported(config: JsAny): Promise<DecoderSupport>
}

/**
 * The answer to `VideoDecoder.isConfigSupported`.
 */
internal external interface DecoderSupport : JsAny {
  val supported: Boolean
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
