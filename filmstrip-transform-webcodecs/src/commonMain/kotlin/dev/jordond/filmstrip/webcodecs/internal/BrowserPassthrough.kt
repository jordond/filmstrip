@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The transmux half of the pipeline: packets read out of the source container and written straight
 * into a new one, with no decode, draw or encode step in between.
 *
 * Pinned to [Mp4OutputFormat], which takes every codec a copy is allowed to carry.
 * [BrowserRender.container] is null here, since it describes an encode that never runs.
 */
internal class BrowserPassthrough(
  private val render: BrowserRender,
  private val sources: SourceCache,
) {
  /**
   * Copies the render's one clip and returns the muxed file, still in memory and not yet handed to
   * anyone.
   *
   * @param onProgress Called after every video packet with the running count and the packet's own
   *   timestamp.
   */
  suspend fun run(onProgress: suspend (Long, Double) -> Unit): PipelineResult {
    val clip = render.clips.first()
    val reader = sources.open(clip.source) ?: throw BrowserExportFailure(unreadable(clip.source))
    val videoTrack = reader.videoTrack() ?: throw BrowserExportFailure(unreadable(clip.source))
    val audioTrack = reader.audioTrack()

    val target = BufferTarget()
    val output =
      Output(
        JsOptions()
          .put("format", Mp4OutputFormat())
          .put("target", target)
          .build(),
      )
    var finished = false
    try {
      val videoSource = EncodedVideoPacketSource(videoTrack.codecString())
      output.addVideoTrack(videoSource, JsOptions().build())
      val audioSource =
        audioTrack?.let { track ->
          val source = EncodedAudioPacketSource(track.codecString())
          output.addAudioTrack(source, JsOptions().build())
          source
        }

      output.start().await()

      val videoSink = EncodedPacketSink(videoTrack)
      val audioSink = audioTrack?.let { EncodedPacketSink(it) }
      // Already the opening the plan resolved to, so the copy lays exactly this window rather than
      // looking for a sync sample of its own. Both tracks open from the same source time, which is
      // what carries sound across everything the copy carries picture across.
      val start = clip.trimStartUs / MICROS_PER_SECOND
      val end = clip.trimEndUs / MICROS_PER_SECOND
      val videoStart = videoSink.openAt(start)
      val audioStart = audioSink?.openAt(start)
      // What every written timestamp is measured from. Taking the earlier of the two keeps both
      // tracks off a negative timestamp, which the muxer refuses, and moves them by the same amount
      // so the gap the source had between them survives.
      val origin = listOfNotNull(videoStart?.timestamp, audioStart?.timestamp).minOrNull() ?: 0.0

      val copied =
        copyPackets(
          sink = videoSink,
          from = videoStart,
          endSeconds = end,
          origin = origin,
          decoderConfig = videoTrack.getDecoderConfig().await(),
          add = { packet, meta -> videoSource.addPacket(packet, meta) },
          onPacket = onProgress,
        )
      if (audioTrack != null && audioSource != null && audioSink != null) {
        copyPackets(
          sink = audioSink,
          from = audioStart,
          endSeconds = end,
          origin = origin,
          decoderConfig = audioTrack.getDecoderConfig().await(),
          add = { packet, meta -> audioSource.addPacket(packet, meta) },
          onPacket = { _, _ -> },
        )
      }

      output.finalize().await()
      val buffer = target.buffer ?: throw BrowserExportFailure(NO_BUFFER)
      finished = true
      return PipelineResult(EncodedFile(buffer, MP4_MIME_TYPE), copied)
    } finally {
      if (!finished) runCatching { output.cancel() }
    }
  }

  /**
   * Reads [sink] from [from] and hands each packet [copyWindow] keeps to [add] in decode order,
   * rebased onto [origin] and stopping at [endSeconds], with the decoder config on the first call
   * and nothing after. Returns how many packets were copied.
   */
  private suspend fun copyPackets(
    sink: EncodedPacketSink,
    from: EncodedPacket?,
    endSeconds: Double,
    origin: Double,
    decoderConfig: JsAny?,
    add: suspend (EncodedPacket, JsAny?) -> Unit,
    onPacket: suspend (Long, Double) -> Unit,
  ): Long {
    var meta = decoderConfig?.let { JsOptions().put("decoderConfig", it).build() }
    var count = 0L
    // Named rather than passed as a nullable, since mediabunny reads an omitted argument and an
    // explicit null differently and only the omitted one means "from the start".
    val stream = PacketStream(if (from == null) sink.packets() else sink.packets(from))

    try {
      copyWindow(
        endSeconds = endSeconds,
        next = { stream.next() },
        timestampOf = { it.timestamp },
        isKeyPacket = { it.isKeyPacket },
      ) { packet ->
        add(packet.rebasedOnto(origin), meta)
        meta = null
        count++
        onPacket(count, (packet.timestamp - origin) * MICROS_PER_SECOND)
      }
    } finally {
      stream.close()
    }
    return count
  }

  private fun unreadable(source: MediaSource): String = "The browser could not read ${source.describe()}."

  private companion object {
    const val NO_BUFFER = "The muxer finalized without producing a buffer."
    const val MP4_MIME_TYPE = "video/mp4"
  }
}

private const val KEY_PACKET_TYPE = "key"

/**
 * Whether this packet is a sync sample, which is where a stream copy is allowed to open and where a
 * group of pictures nothing before it presents into begins.
 */
private val EncodedPacket.isKeyPacket: Boolean
  get() = type.toString() == KEY_PACKET_TYPE

/**
 * The packet this copy opens on for a window opening at [seconds], or null when the track names
 * none there and the walk reads from its own start instead.
 */
private suspend fun EncodedPacketSink.openAt(seconds: Double): EncodedPacket? = getKeyPacket(seconds).await()

/**
 * The same packet with [origin] taken off its timestamp, or the packet itself when there is nothing
 * to take off.
 */
private fun EncodedPacket.rebasedOnto(origin: Double): EncodedPacket =
  if (origin == 0.0) this else clone(JsOptions().put("timestamp", timestamp - origin).build())

private suspend fun InputVideoTrack.codecString(): String =
  getCodec().await()?.toString()
    ?: throw BrowserExportFailure("mediabunny read a video track with no codec name.")

private suspend fun InputAudioTrack.codecString(): String =
  getCodec().await()?.toString()
    ?: throw BrowserExportFailure("mediabunny read an audio track with no codec name.")

/**
 * Calls [EncodedVideoPacketSource.add], leaving [meta] out of the call entirely once it is null
 * rather than passing `null` for it, which mediabunny's own validation rejects.
 */
private suspend fun EncodedVideoPacketSource.addPacket(
  packet: EncodedPacket,
  meta: JsAny?,
) {
  if (meta != null) add(packet, meta).await() else add(packet).await()
}

private suspend fun EncodedAudioPacketSource.addPacket(
  packet: EncodedPacket,
  meta: JsAny?,
) {
  if (meta != null) add(packet, meta).await() else add(packet).await()
}

/**
 * Encoded packets, pulled one at a time so a copy paces itself the same way a decode does.
 */
internal class PacketStream(
  private val iterator: EncodedPacketIterator,
) {
  private var finished = false

  suspend fun next(): EncodedPacket? {
    if (finished) return null
    val step = iterator.next().await()
    if (step.done) {
      finished = true
      return null
    }
    return step.value
  }

  /**
   * Releases the sink's read-ahead. Iteration that stops before the track's end has to say so, or
   * the sink keeps buffering packets nobody will read.
   */
  fun close() {
    if (finished) return
    finished = true
    iterator.`return`(null)
  }
}
