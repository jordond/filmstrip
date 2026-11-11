@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.videoSlots
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.PacketStream
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.copyPacketsFrom
import dev.jordond.filmstrip.webcodecs.internal.interop.BufferTarget
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedPacketSink
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedVideoPacketSource
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Mp4OutputFormat
import dev.jordond.filmstrip.webcodecs.internal.interop.Output
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * How many frames a stream copy that ends before its source does writes, against the count the shared prefix gives
 * over the source's own packets.
 *
 * No browser encoder reorders its frames, so the source is a clip's packets muxed again in the decode order the other
 * backends' long fixture holds, two B-frames behind each reference. A grid can then end on a whole reorder group or
 * inside one, and only the second writes references presented past it. Nothing is decoded, so the packets' own data
 * never has to match the order they are muxed in.
 */
class BrowserCopyGridMediaTest {
  private val engine =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    )

  @Test
  fun aCopyEndingOnAWholeReorderGroupWritesExactlyTheGrid() =
    runTest {
      val written = copiedWindow(WHOLE_GROUP_END)

      withClue("the copy wrote $written") {
        written.packets shouldBe written.prefix
        written.packets shouldBe written.slots
        written.slotsCovered shouldBe (0 until written.slots).toList()
      }
    }

  @Test
  fun aCopyEndingInsideAReorderGroupWritesTheSharedPrefix() =
    runTest {
      val written = copiedWindow(SPLIT_GROUP_END)

      withClue("the window was meant to end inside a reorder group") { (written.prefix > written.slots) shouldBe true }
      withClue("the copy wrote $written") {
        written.packets shouldBe written.prefix
        written.slotsCovered.take(written.slots) shouldBe (0 until written.slots).toList()
      }
    }

  // A window of a frame or two leaves a track with too few packets to time its last one from the one before, so the
  // file's length is read against the frames it holds.
  @Test
  fun aCopyOfAFrameOrTwoRunsToTheEndOfItsLastFrame() =
    runTest {
      SHORT_ENDS.forEach { end ->
        val written = copiedWindow(end)

        withClue("the copy to $end wrote $written") {
          written.packets shouldBe written.prefix
          ((written.duration - written.lastFrameEnd).absoluteValue <= written.frame) shouldBe true
        }
      }
    }

  @Test
  fun aPacketReadPastItsCapIsRefused() =
    runTest {
      val reader = SourceReader.of(MediaSource.Bytes(reorderedClip())) ?: error("the reordered clip did not open")
      try {
        val track = reader.videoTrack() ?: error("the reordered clip has no video track")

        val refused =
          assertFailsWith<BrowserExportFailure> {
            EncodedPacketSink(track).copyPacketsFrom(null, SPLIT_GROUP_END.toDouble(DurationUnit.SECONDS), SMALL_CAP)
          }

        refused.message.shouldNotBeNull() shouldContain "more than $SMALL_CAP video packets"
      } finally {
        reader.close()
      }
    }

  private suspend fun copiedWindow(end: Duration): CopiedWindow {
    val source = MediaSource.Bytes(reorderedClip())
    val composition = compositionOf { clip(source) { trim(TimeRange.of(Duration.ZERO, end)) } }
    val negotiated = engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    val plan =
      when (val verdict = engine.plan(composition, ExportSpec())) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
      }
    withClue("an untouched window from the source's start is meant to copy") { plan.path shouldBe ExportPath.Copy }

    val statuses = engine.export(plan, MediaSink.Uri("")).toList()
    statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()?.let { throw AssertionError(it.error.message) }
    val success = statuses.filterIsInstance<ExportStatus.Success>().singleOrNull().shouldNotBeNull()

    val rate = negotiated.output.frameRate
    val presented = packetsOf(outputOf(success)).map { it.pts }
    val prefix = negotiated.copyPrefixOf(packetsOf(source)).shouldBeInstanceOf<CopyPrefix.Packets>().count
    withClue("the plan counts the copy's packets before it is exported") {
      negotiated.copyPrefix?.count shouldBe prefix
    }
    return CopiedWindow(
      slots = negotiated.videoSlots.toInt(),
      prefix = prefix,
      packets = presented.size,
      slotsCovered = presented.sorted().map { outputSlotOf(it, rate).toInt() },
      frame = outputSlotTime(1, rate),
      lastFrameEnd = presented.max() + outputSlotTime(1, rate),
      duration = success.info.duration,
    )
  }

  /**
   * What a copy wrote, read off the container, against what the plan's grid holds.
   *
   * @property slots How many slots the plan's grid holds.
   * @property prefix How many packets the shared prefix gives over the source's packets.
   * @property packets How many video packets the file holds.
   * @property slotsCovered The slot each written frame is presented on, in presentation order.
   * @property frame How long one slot of the plan's grid lasts.
   * @property lastFrameEnd Where the last frame the file presents ends, one slot after it starts.
   * @property duration How long the finished export reports the file runs.
   */
  private data class CopiedWindow(
    val slots: Int,
    val prefix: Int,
    val packets: Int,
    val slotsCovered: List<Int>,
    val frame: Duration,
    val lastFrameEnd: Duration,
    val duration: Duration,
  )

  // A plain clip's packets muxed again in DECODE_ORDER, each one presented on its frame of the order and typed a key
  // packet where the order marks one.
  private suspend fun reorderedClip(): ByteArray {
    val plain = makeClip(frames = DECODE_ORDER.size, frameRate = FRAME_RATE)
    val reader = SourceReader.of(MediaSource.Bytes(plain)) ?: error("the plain clip did not open")
    try {
      val track = reader.videoTrack() ?: error("the plain clip has no video track")
      val target = BufferTarget()
      val output = Output(JsOptions().put("format", Mp4OutputFormat()).put("target", target).build())
      val source = EncodedVideoPacketSource("avc")
      output.addVideoTrack(source, JsOptions().put("frameRate", FRAME_RATE).build())
      output.start().await()

      val stream = PacketStream(EncodedPacketSink(track).packets()) {}
      try {
        var meta: JsOptions? = JsOptions().put("decoderConfig", track.getDecoderConfig().await())
        DECODE_ORDER.forEach { frame ->
          val packet = stream.next() ?: error("the plain clip ran out of packets")
          val reordered =
            packet.clone(
              JsOptions()
                .put("timestamp", frame / FRAME_RATE.toDouble())
                .put("duration", 1.0 / FRAME_RATE)
                .put("type", if (frame in KEY_FRAMES) "key" else "delta")
                .build(),
            )
          val first = meta
          if (first != null) source.add(reordered, first.build()).await() else source.add(reordered).await()
          meta = null
        }
      } finally {
        stream.close()
      }
      output.finalize().await()
      return (target.buffer ?: error("the reordered clip's muxer produced no buffer")).toByteArray()
    } finally {
      reader.close()
    }
  }

  // Every video packet of the file, in decode order, read without decoding any.
  private suspend fun packetsOf(source: MediaSource): List<CopyPacket> {
    val reader = SourceReader.of(source) ?: error("$source did not open")
    try {
      val track = reader.videoTrack() ?: error("$source has no video track")
      val stream = PacketStream(EncodedPacketSink(track).packets()) {}
      try {
        return buildList {
          while (true) {
            val packet = stream.next() ?: break
            add(CopyPacket(packet.timestamp.seconds, packet.type.toString() == "key"))
          }
        }
      } finally {
        stream.close()
      }
    } finally {
      reader.close()
    }
  }

  private companion object {
    const val FRAME_RATE = 30
    const val SMALL_CAP = 5

    // The long fixture's first 63 packets as the frame each presents, in decode order: two B-frames reordered behind
    // each reference, a run with none ahead of each sync sample, and a sync sample every 29 frames.
    val DECODE_ORDER =
      listOf(0, 4, 2, 1, 3, 8, 6, 5, 7, 12, 10, 9, 11, 16, 14, 13, 15, 20, 18, 17, 19) + (21..28) +
        listOf(29, 33, 31, 30, 32, 37, 35, 34, 36, 41, 39, 38, 40, 45, 43, 42, 44, 47, 46) + (48..57) +
        listOf(58, 62, 60, 59, 61)
    val KEY_FRAMES = setOf(0, 29, 58)

    // Thirty slots close on a whole reorder group, and thirty one close with the frame on the last of them decoding
    // behind two references presented past it.
    // From under one audio access unit to two slots, the second of which decodes behind references past the grid.
    val SHORT_ENDS = listOf(21.milliseconds, 22.milliseconds, 40.milliseconds, 67.milliseconds)

    val WHOLE_GROUP_END = 1_010.milliseconds
    val SPLIT_GROUP_END = 1_040.milliseconds
  }
}
