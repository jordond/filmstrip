@file:JsModule("mediabunny")
@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsBoolean
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.definedExternally

// mediabunny, declared once for both browser targets. A static `@JsModule` import is what lets the
// bundler tree-shake the package and what stops it being instantiated twice, which a dynamic
// `import('mediabunny')` inside an interop string did.

/**
 * Every container mediabunny can demux. Passed to [Input] so a source is sniffed rather than
 * declared.
 */
internal external val ALL_FORMATS: JsArray<JsAny>

/**
 * One file opened for reading.
 *
 * Nothing is read until a method is called, and [dispose] cancels whatever reading is in flight,
 * which is how a cancelled export stops a range-requesting [UrlSource].
 */
internal external class Input(
  options: JsAny,
) : JsAny {
  /**
   * Every video track, in the order the container lists them.
   */
  fun getVideoTracks(): Promise<JsArray<InputVideoTrack>>

  /**
   * Every audio track, in the order the container lists them.
   */
  fun getAudioTracks(): Promise<JsArray<InputAudioTrack>>

  /**
   * The file's duration, the largest end timestamp among all its tracks. Wider than any one
   * track's own duration, so this is what a prober reports rather than the video track's.
   */
  fun computeDuration(): Promise<JsNumber>

  /**
   * The container format mediabunny sniffed the file as.
   */
  fun getFormat(): Promise<InputFormat>

  fun dispose()
}

/**
 * A container format mediabunny reads.
 *
 * @property mimeType The format's base MIME type, such as `video/mp4` or `video/x-matroska`.
 */
internal external interface InputFormat : JsAny {
  val mimeType: String
}

/**
 * The video track of an [Input].
 */
internal external interface InputVideoTrack : JsAny {
  fun computeDuration(): Promise<JsNumber>

  /**
   * Storage dimensions, before the pixel aspect ratio or the rotation are applied.
   */
  fun getCodedWidth(): Promise<JsNumber>

  fun getCodedHeight(): Promise<JsNumber>

  /**
   * Playback dimensions, with both the pixel aspect ratio and the rotation applied.
   */
  fun getDisplayWidth(): Promise<JsNumber>

  fun getDisplayHeight(): Promise<JsNumber>

  /**
   * Width of a stored pixel over its height, reduced. Square pixels read as `1:1`.
   */
  fun getPixelAspectRatio(): Promise<Rational>

  fun getRotation(): Promise<JsNumber>

  fun getCodec(): Promise<JsString?>

  /**
   * The full `codecs` parameter string, such as `hvc1.2.4.L123.B0`. The only field this reads
   * from it is HEVC's profile digit, the one codec whose bit depth is findable this way.
   */
  fun getCodecParameterString(): Promise<JsString?>

  fun getColorSpace(): Promise<VideoColorSpaceInit>

  /**
   * Whether the track's packets can decode to pictures with transparent pixels.
   */
  fun canBeTransparent(): Promise<JsBoolean>

  fun computePacketStats(targetPacketCount: Int): Promise<PacketStats>

  /**
   * The config a `VideoDecoder` needs to decode this track's packets, or null when the codec is
   * unknown.
   */
  fun getDecoderConfig(): Promise<JsAny?>
}

/**
 * A reduced fraction, the shape mediabunny reports a pixel aspect ratio in.
 */
internal external interface Rational : JsAny {
  val num: Double

  val den: Double
}

/**
 * A track's color space, in the vocabulary WebCodecs itself uses.
 */
internal external interface VideoColorSpaceInit : JsAny {
  val primaries: JsString?

  val transfer: JsString?

  /**
   * The Y'CbCr matrix, such as `bt2020-ncl`. Read only to prove what a written file was tagged
   * with, since the prober decides HDR on the transfer alone.
   */
  val matrix: JsString?
}

/**
 * What a bounded walk of a track's packets found. `averagePacketRate` is the frame rate for a video
 * track.
 */
internal external interface PacketStats : JsAny {
  val packetCount: Int

  val averagePacketRate: Double
}

/**
 * One compressed chunk of a track, read without decoding it.
 */
internal external interface EncodedPacket : JsAny {
  val data: Uint8Array

  val type: JsString

  val timestamp: Double

  val duration: Double

  val byteLength: Int

  /**
   * The same packet with the fields named in [options] replaced, the timestamp among them. A copy
   * that opens part way into a source rebases every packet through here, since the muxer refuses a
   * negative timestamp.
   */
  fun clone(options: JsAny): EncodedPacket
}

/**
 * Reads a track's packets without decoding them, for a stream copy that repackages the compressed
 * data into a new container.
 */
internal external class EncodedPacketSink(
  track: JsAny,
) : JsAny {
  fun getFirstKeyPacket(): Promise<EncodedPacket?>

  /**
   * The track's first packet in decode order, or null when it holds none, read with mediabunny's packet retrieval
   * [options].
   */
  fun getFirstPacket(options: JsAny = definedExternally): Promise<EncodedPacket?>

  /**
   * The last key packet whose timestamp is at or before [timestamp], in seconds, or null when the
   * track has none before it. This is the sample a relaxed seek lands on.
   */
  fun getKeyPacket(timestamp: Double): Promise<EncodedPacket?>

  /**
   * The packet after [packet] in decode order, or null when [packet] is the track's last. [options] is mediabunny's
   * packet retrieval options, where `metadataOnly` reads the timestamp and type without the packet's data.
   */
  fun getNextPacket(
    packet: EncodedPacket,
    options: JsAny = definedExternally,
  ): Promise<EncodedPacket?>

  /**
   * Packets from [startPacket] up to but excluding [endPacket], in decode order. Left at its
   * default on either end reads from the track's start, or through to its end.
   *
   * The parameters default to `null` here rather than take an explicit `null` call, because
   * mediabunny checks `!== undefined` and a call that names the argument passes JavaScript `null`,
   * which fails that check. Leaving a default unset is what compiles to an omitted argument.
   */
  fun packets(
    startPacket: EncodedPacket? = definedExternally,
    endPacket: EncodedPacket? = definedExternally,
  ): MediabunnyIterator<EncodedPacket>
}

/**
 * The async iterator a mediabunny sink hands out, yielding one [T] a step.
 */
internal external interface MediabunnyIterator<T : JsAny> : JsAny {
  fun next(): Promise<MediabunnyStep<T>>

  /**
   * Ends iteration early and releases what the sink holds for it. Needed whenever a reader stops short of the track's
   * end.
   */
  fun `return`(value: JsAny?): Promise<MediabunnyStep<T>>
}

/**
 * One step of [MediabunnyIterator].
 */
internal external interface MediabunnyStep<T : JsAny> : JsAny {
  val done: Boolean

  val value: T?
}

/**
 * Decodes a track's frames.
 */
internal external class VideoSampleSink : JsAny {
  constructor(videoTrack: InputVideoTrack)

  /**
   * [options] carries the decoder hint. `hardwareAcceleration: "prefer-software"` is what makes a
   * ten-bit source hand out a readable `I420P10` frame rather than an opaque one, and a browser is
   * free to refuse the config outright, so a caller has to be ready to open the sink without it.
   */
  constructor(videoTrack: InputVideoTrack, options: JsAny)

  /**
   * Frames in `[startTimestamp, endTimestamp)`, both in seconds. The sink seeks to the key frame
   * before [startTimestamp] rather than decoding from zero.
   */
  fun samples(
    startTimestamp: Double,
    endTimestamp: Double,
  ): MediabunnyIterator<VideoSample>

  /**
   * The one frame presented at [timestamp], in seconds, or null before the track's first frame. Past
   * the end of the track that is the last frame. The sink decodes forward from the key frame at or
   * before it, which is what a seek costs here.
   */
  fun getSample(timestamp: Double): Promise<VideoSample?>
}

/**
 * A decoded frame, either yielded by a sink or wrapped around something drawable on the way to the
 * encoder.
 */
internal external class VideoSample(
  data: JsAny,
  init: JsAny,
) : JsAny {
  /**
   * The decoded frame's pixel layout, or null where the decoder keeps it opaque.
   */
  val format: JsString?

  val microsecondTimestamp: Double

  val microsecondDuration: Double

  val codedWidth: Int

  val codedHeight: Int

  fun toVideoFrame(): VideoFrame

  fun allocationSize(options: JsAny): Int

  /**
   * Copies the sample's pixels into [destination] and answers with where each plane landed.
   *
   * [options] left empty copies the planes as they are stored, which is the only way a ten-bit
   * frame comes out at all: naming `I420P10` explicitly is refused as a non-RGB target.
   */
  fun copyTo(
    destination: JsAny,
    options: JsAny,
  ): Promise<JsArray<PlaneLayout>>

  fun close()
}

/**
 * Decodes a track's samples.
 */
internal external class AudioSampleSink(
  audioTrack: InputAudioTrack,
) : JsAny {
  /**
   * Samples in `[startTimestamp, endTimestamp)`, both in seconds.
   */
  fun samples(
    startTimestamp: Double,
    endTimestamp: Double,
  ): MediabunnyIterator<AudioSample>
}

/**
 * A decoded chunk of audio, either yielded by a sink or built from an [AudioBuffer] on the way to
 * the encoder.
 */
internal external class AudioSample(
  init: JsAny,
) : JsAny {
  val microsecondTimestamp: Double

  val microsecondDuration: Double

  val sampleRate: Int

  val numberOfFrames: Int

  val numberOfChannels: Int

  fun toAudioBuffer(): AudioBuffer

  fun close()
}

/**
 * An [AudioBuffer] paired with the timestamp and duration of the sample it came from.
 */
internal external interface WrappedAudioBuffer : JsAny {
  val buffer: AudioBuffer

  val timestamp: Double

  val duration: Double
}

/**
 * Decodes a track's samples straight into Web Audio [AudioBuffer]s.
 */
internal external class AudioBufferSink(
  audioTrack: InputAudioTrack,
) : JsAny {
  fun getBuffer(timestamp: Double): Promise<WrappedAudioBuffer?>

  /**
   * Buffers in `[startTimestamp, endTimestamp)`, both in seconds.
   */
  fun buffers(
    startTimestamp: Double,
    endTimestamp: Double,
  ): MediabunnyIterator<WrappedAudioBuffer>
}

/**
 * A source that reads an in-memory buffer.
 */
internal external class BufferSource(
  buffer: JsAny,
) : JsAny

/**
 * A source that range-requests a URL, so a large file is never buffered whole.
 */
internal external class UrlSource(
  url: String,
) : JsAny

/**
 * The file being written.
 */
internal external class Output(
  options: JsAny,
) : JsAny {
  fun addVideoTrack(
    source: JsAny,
    metadata: JsAny,
  )

  fun addAudioTrack(
    source: JsAny,
    metadata: JsAny,
  )

  fun start(): Promise<JsAny?>

  fun finalize(): Promise<JsAny?>

  fun cancel(): Promise<JsAny?>
}

/**
 * Encodes raw frames and muxes them. [add] resolves once the encoder and the writer are ready for
 * another frame, which is the backpressure the pipeline waits on.
 */
internal external class VideoSampleSource(
  encodingConfig: JsAny,
) : JsAny {
  fun add(videoSample: VideoSample): Promise<JsAny?>

  fun add(
    videoSample: VideoSample,
    encodeOptions: JsAny,
  ): Promise<JsAny?>
}

/**
 * The most basic video source: pipes already-encoded packets straight into the output, for a
 * stream copy.
 */
internal external class EncodedVideoPacketSource(
  codec: String,
) : JsAny {
  /**
   * [meta] carries the decoder config, required on the first call and ignored after. Left at its
   * default once it is no longer needed: mediabunny checks `!== undefined`, so a call that names
   * the argument as `null` fails validation where an omitted argument would not.
   */
  fun add(
    packet: EncodedPacket,
    meta: JsAny? = definedExternally,
  ): Promise<JsAny?>
}

/**
 * The most basic audio source: pipes already-encoded packets straight into the output, for a
 * stream copy.
 */
internal external class EncodedAudioPacketSource(
  codec: String,
) : JsAny {
  /**
   * [meta] carries the decoder config, required on the first call and ignored after. Left at its
   * default once it is no longer needed: mediabunny checks `!== undefined`, so a call that names
   * the argument as `null` fails validation where an omitted argument would not.
   */
  fun add(
    packet: EncodedPacket,
    meta: JsAny? = definedExternally,
  ): Promise<JsAny?>
}

/**
 * Encodes raw audio samples and muxes them.
 */
internal external class AudioSampleSource(
  encodingConfig: JsAny,
) : JsAny {
  fun add(audioSample: AudioSample): Promise<JsAny?>
}

/**
 * Encodes Web Audio [AudioBuffer]s and muxes them.
 */
internal external class AudioBufferSource(
  encodingConfig: JsAny,
) : JsAny {
  fun add(audioBuffer: AudioBuffer): Promise<JsAny?>
}

/**
 * A requested encoding quality, built from an explicit bitrate here.
 */
internal external class Quality(
  options: JsAny,
) : JsAny

/**
 * Collects the muxed file in memory. [buffer] is null until the output is finalized.
 */
internal external class BufferTarget : JsAny {
  val buffer: ArrayBuffer?
}

/**
 * The MP4 container.
 */
internal external class Mp4OutputFormat : JsAny

/**
 * The WebM container, for VP9.
 */
internal external class WebMOutputFormat : JsAny
