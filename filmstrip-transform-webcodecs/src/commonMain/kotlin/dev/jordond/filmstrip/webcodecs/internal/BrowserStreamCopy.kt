@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.transform.internal.copy.COPY_PACKET_MARGIN
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketRead
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketWindow
import dev.jordond.filmstrip.transform.internal.copy.MAX_COPY_PACKET_RATE
import dev.jordond.filmstrip.transform.internal.copy.copyPacketCap
import dev.jordond.filmstrip.webcodecs.internal.interop.BufferTarget
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedAudioPacketSource
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedPacket
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedPacketSink
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedVideoPacketSource
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.InputVideoTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Mp4OutputFormat
import dev.jordond.filmstrip.webcodecs.internal.interop.Output
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The copy half of the pipeline: packets read out of the source container and written straight
 * into a new one, with no decode, draw or encode step in between.
 *
 * Pinned to [Mp4OutputFormat], which takes every codec a copy is allowed to carry.
 * [BrowserRender.videoEncoding] is null here, since it describes an encode that never runs.
 */
internal class BrowserStreamCopy(
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
    val reader = sources.open(clip.source) ?: throw BrowserExportFailure(unreadableMessage(clip.source))
    val videoTrack = reader.videoTrack() ?: throw BrowserExportFailure(unreadableMessage(clip.source))
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

      // A window that ends before the source does keeps the prefix of the video's packets the plan counted, which stops
      // on the frames its grid holds. The audio runs to the window's own end.
      val keptVideo = render.copyVideoPackets
      val copied =
        copyPackets(
          sink = videoSink,
          from = videoStart,
          origin = origin,
          decoderConfig = videoTrack.getDecoderConfig().await(),
          keeps = { count, _ -> keptVideo == null || count < keptVideo },
          add = { packet, meta -> videoSource.addPacket(packet, meta) },
          onPacket = onProgress,
        )
      if (audioTrack != null && audioSource != null && audioSink != null) {
        copyPackets(
          sink = audioSink,
          from = audioStart,
          origin = origin,
          decoderConfig = audioTrack.getDecoderConfig().await(),
          keeps = { _, packet -> packet.timestamp < end },
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
   * Reads [sink] from [from] and hands each packet to [add] in decode order, rebased onto [origin], until [keeps]
   * turns one down. The decoder config goes with the first call and nothing after. Returns how many packets were
   * copied.
   *
   * @param keeps Whether the packet read, given the count of packets copied so far, is copied too, which ends the walk
   * when it is not.
   */
  private suspend fun copyPackets(
    sink: EncodedPacketSink,
    from: EncodedPacket?,
    origin: Double,
    decoderConfig: JsAny?,
    keeps: (Long, EncodedPacket) -> Boolean,
    add: suspend (EncodedPacket, JsAny?) -> Unit,
    onPacket: suspend (Long, Double) -> Unit,
  ): Long {
    var meta = decoderConfig?.let { JsOptions().put("decoderConfig", it).build() }
    var count = 0L
    // Named rather than passed as a nullable, since mediabunny reads an omitted argument and an
    // explicit null differently and only the omitted one means "from the start".
    val stream = PacketStream(if (from == null) sink.packets() else sink.packets(from)) {}

    try {
      while (true) {
        val packet = stream.next() ?: break
        if (!keeps(count, packet)) break
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
 * The video packets of [window]'s source in decode order, from the sync sample its copy opens on through
 * [COPY_PACKET_MARGIN] past its end, read through [copyPacketsFrom] and capped at [copyPacketCap] over the window.
 */
internal suspend fun SourceCache.copyPacketsOf(window: CopyPacketWindow): CopyPacketRead {
  val track = open(window.source)?.videoTrack() ?: return CopyPacketRead.Failed(unreadableMessage(window.source))
  val sink = EncodedPacketSink(track)
  return try {
    val from = sink.openAt(window.from.toDouble(DurationUnit.SECONDS))
    CopyPacketRead.Read(
      sink.copyPacketsFrom(from, window.to.toDouble(DurationUnit.SECONDS), copyPacketCap(window.from, window.to)),
    )
  } catch (failure: BrowserExportFailure) {
    CopyPacketRead.Failed(failure.message ?: unreadableMessage(window.source))
  }
}

/**
 * This track's packets in decode order from [from], or from its first packet when [from] is null, through the first
 * one presented at or past [endSeconds] and [COPY_PACKET_MARGIN]. Only each packet's timestamp and type are read, never
 * its data.
 *
 * @param cap The most packets the read lists, [copyPacketCap] over the copy's window.
 * @throws BrowserExportFailure When the read lists more than [cap] packets.
 */
internal suspend fun EncodedPacketSink.copyPacketsFrom(
  from: EncodedPacket?,
  endSeconds: Double,
  cap: Int,
): List<CopyPacket> {
  val metadataOnly = JsOptions().put("metadataOnly", true).build()
  val closes = endSeconds + COPY_PACKET_MARGIN.toDouble(DurationUnit.SECONDS)
  return buildList {
    var packet = from ?: getFirstPacket(metadataOnly).await()
    while (packet != null) {
      if (size >= cap) throw BrowserExportFailure(pastCopyCap(cap, closes))
      add(CopyPacket(packet.timestamp.seconds, packet.isKeyPacket))
      if (packet.timestamp >= closes) break
      packet = getNextPacket(packet, metadataOnly).await()
    }
  }
}

// Why a copy whose read listed more than cap packets up to closes seconds is refused.
private fun pastCopyCap(
  cap: Int,
  closes: Double,
): String =
  "A copy lists more than $cap video packets up to $closes s, past the $MAX_COPY_PACKET_RATE fps a copy reads its " +
    "packets at."

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
internal typealias PacketStream = PulledStream<EncodedPacket>
