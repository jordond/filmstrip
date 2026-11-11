package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How many frames a copy that ends before its source does writes, against the count the shared prefix gives over the
 * source's own packets, and where its audio ends.
 *
 * The long fixture reorders two B-frames behind each reference, so a grid can end on a whole reorder group or inside
 * one, and only the second writes references presented past it.
 */
class Media3CopyGridMediaTest {
  private val probe = Media3ExportProbe()
  private val engine =
    media3ExportEngine(ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build())

  @Test
  fun aCopyEndingOnAWholeReorderGroupWritesExactlyTheGrid() =
    runTest(timeout = TIMEOUT) {
      val written = copiedWindow(WHOLE_GROUP_END)

      withClue("the copy wrote $written") {
        written.packets shouldBe written.prefix
        written.packets shouldBe written.slots
        written.slotsCovered shouldBe (0 until written.slots).toList()
      }
    }

  @Test
  fun aCopyEndingInsideAReorderGroupWritesTheSharedPrefix() =
    runTest(timeout = TIMEOUT) {
      val written = copiedWindow(SPLIT_GROUP_END)

      withClue("the window was meant to end inside a reorder group") { (written.prefix > written.slots) shouldBe true }
      withClue("the copy wrote $written") {
        written.packets shouldBe written.prefix
        written.slotsCovered.take(written.slots) shouldBe (0 until written.slots).toList()
      }
    }

  // The audio rides in a sequence of its own, clipped at the window's end rather than the grid's. Copied audio ends on
  // a whole access unit, and which side of the window's end that unit lands on is media3's own call, so the bound is
  // one unit.
  @Test
  fun aCopysAudioEndsWithinOneAccessUnitOfTheTrimEnd() =
    runTest(timeout = TIMEOUT) {
      val unit = accessUnit()
      listOf(WHOLE_GROUP_END, SPLIT_GROUP_END).forEach { end ->
        val written = copiedWindow(end)

        withClue("the copy to $end wrote $written, against a $unit access unit") {
          ((written.audio.shouldNotBeNull() - end).absoluteValue < unit) shouldBe true
        }
      }
    }

  // Two sequences are only a copy when media3 transmuxes both, so every sample the file holds is the source's own, byte
  // for byte and in the source's decode order.
  @Test
  fun aCopyWritesTheSourcesOwnSamples() =
    runTest(timeout = TIMEOUT) {
      val written = copiedWindow(SPLIT_GROUP_END)
      val source = probe.fixtureFile(CLIP_LONG)

      listOf(VIDEO, AUDIO).forEach { kind ->
        val copied = written.samples.getValue(kind).shouldNotBeEmpty()
        withClue("the copy's $kind samples against the source's") {
          copied.map { it.digest } shouldBe samplesInDecodeOrder(source, kind).take(copied.size).map { it.digest }
        }
      }
    }

  // An AAC track opens on a priming unit presented before zero, so the copy keeps the source's own offset between its
  // first audio and first video sample rather than lining the two up.
  @Test
  fun aCopysAudioOpensAsFarFromItsVideoAsTheSourcesDoes() =
    runTest(timeout = TIMEOUT) {
      val written = copiedWindow(SPLIT_GROUP_END).samples
      val source = listOf(VIDEO, AUDIO).associateWith { samplesInDecodeOrder(probe.fixtureFile(CLIP_LONG), it) }

      withClue("the copy's first audio sample against its first video sample, then the source's") {
        written.audioLead() shouldBe source.audioLead()
      }
    }

  // A snapped cut opens on a sync sample past the source's start, where the audio access unit it opens on straddles the
  // cut rather than sitting on it, so the copy's lead matches the source's at that cut to within one unit.
  @Test
  fun aSnappedCopysAudioOpensAsFarFromItsVideoAsTheSourcesDoesAtTheCut() =
    runTest(timeout = TIMEOUT) {
      val unit = accessUnit()
      val written = copiedWindow(TimeRange.of(SNAPPED_START, SNAPPED_END), snapWithin = SNAP_WITHIN)
      val cut = written.start
      val sourceAudio = samplesInDecodeOrder(probe.fixtureFile(CLIP_LONG), AUDIO)
      val sourceLead = cut - sourceAudio.filter { it.time <= cut }.maxOf { it.time }

      withClue("a snapped cut is meant to open past the source's start") { (cut > Duration.ZERO) shouldBe true }
      withClue("the copy cut at $cut opened ${written.samples.audioLead()} ahead, against the source's $sourceLead") {
        ((written.samples.audioLead() - sourceLead).absoluteValue <= unit) shouldBe true
      }
    }

  // A window of a frame or two leaves a track with too few samples for a muxer to time its last one from, so each
  // track's end is read against the plan's grid and the units the copy holds.
  @Test
  fun aCopyOfAFrameOrTwoEndsItsTracksWhereItsSamplesDo() =
    runTest(timeout = TIMEOUT) {
      val unit = accessUnit()
      val wroteAudio = mutableSetOf<Boolean>()
      SHORT_ENDS.forEach { end ->
        val written = copiedWindow(end)
        // media3 copies the units that end inside the window, and the source's first heard unit opens on zero.
        val units = (end / unit).toInt()
        wroteAudio += units > 0

        withClue("the copy to $end wrote $written, where $units units of $unit end inside the window") {
          written.packets shouldBe written.prefix
          if (written.prefix == written.slots) written.video shouldBe written.gridEnd
          (written.video >= written.gridEnd) shouldBe true
          if (units == 0) {
            written.audio.shouldBeNull()
            written.adjusted shouldBe listOf(AdjustmentKind.AudioDropped)
          } else {
            (written.audio.shouldNotBeNull() - unit * units).absoluteValue shouldBeLessThan CONTAINER_TICK
            written.adjusted shouldBe emptyList()
          }
          val longest = maxOf(written.video, written.audio ?: Duration.ZERO)
          (written.duration - longest).absoluteValue shouldBeLessThan CONTAINER_TICK
        }
      }
      withClue("the windows were meant to reach a copy with audio to hear and one without") {
        wroteAudio shouldBe setOf(true, false)
      }
    }

  // How far before the first video sample the first audio sample is presented.
  private fun Map<String, List<Sample>>.audioLead(): Duration =
    getValue(VIDEO).minOf { it.time } - getValue(AUDIO).minOf { it.time }

  private suspend fun accessUnit(): Duration =
    AAC_FRAME_SAMPLES.seconds /
      probe
        .infoOf(probe.fixture(CLIP_LONG))
        .audio
        .shouldNotBeNull()
        .sampleRate

  private suspend fun copiedWindow(end: Duration): CopiedWindow = copiedWindow(TimeRange.of(Duration.ZERO, end))

  private suspend fun copiedWindow(
    trim: TimeRange,
    snapWithin: Duration = Duration.ZERO,
  ): CopiedWindow {
    val sourceFile = probe.fixtureFile(CLIP_LONG)
    val source = MediaSource.of(sourceFile.path)
    val composition = Composition(listOf(Track(listOf(Clip(source, trim = trim, snapWithin = snapWithin)))))
    val negotiated =
      when (val result = engine.resolve(composition, ExportSpec())) {
        is ResolveResult.Resolved -> result.composition
        is ResolveResult.Refused -> throw AssertionError("the copy would not lower: ${result.error.message}")
      }
    val plan = probe.plan(composition, ExportSpec())
    withClue("an untouched window is meant to copy") { plan.path shouldBe ExportPath.Copy }

    val finished = probe.finish(plan)
    val file = File(finished.output.shouldBeInstanceOf<MediaSink.Path>().path)
    val rate = negotiated.output.frameRate
    val presented = videoSampleTimes(file)
    return CopiedWindow(
      start =
        negotiated.tracks
          .first()
          .clips
          .single()
          .start,
      slots = negotiated.videoSlots.toInt(),
      prefix = negotiated.copyPrefixOf(packetsInDecodeOrder(sourceFile)).shouldBeInstanceOf<CopyPrefix.Packets>().count,
      packets = presented.size,
      gridEnd = outputSlotTime(negotiated.videoSlots, rate),
      video = videoTrackLength(file),
      audio = if (file.holds(AUDIO)) audioTrackLength(file) else null,
      duration = finished.info.duration,
      adjusted = finished.adjustments.map { it.kind },
      slotsCovered = presented.map { outputSlotOf(it, rate).toInt() },
      samples = listOf(VIDEO, AUDIO).filter { file.holds(it) }.associateWith { samplesInDecodeOrder(file, it) },
    ).also { file.delete() }
  }

  /**
   * What a copy wrote, read off the container, against what the plan's grid holds.
   *
   * @property start Where in the source the copy opens, after any snap.
   * @property slots How many slots the plan's grid holds.
   * @property prefix How many packets the shared prefix gives over the source's packets.
   * @property packets How many video samples the file holds.
   * @property gridEnd Where the plan's grid ends.
   * @property video How long the video track runs.
   * @property audio How long the audio track runs, or null when the file holds none.
   * @property duration How long the finished export reports the file runs.
   * @property adjusted The kind of every adjustment the finished export reports.
   * @property slotsCovered The slot each written frame is presented on, in presentation order.
   * @property samples Every sample the file holds by track kind, in decode order.
   */
  private data class CopiedWindow(
    val start: Duration,
    val slots: Int,
    val prefix: Int,
    val packets: Int,
    val gridEnd: Duration,
    val video: Duration,
    val audio: Duration?,
    val duration: Duration,
    val adjusted: List<AdjustmentKind>,
    val slotsCovered: List<Int>,
    val samples: Map<String, List<Sample>>,
  ) {
    override fun toString(): String =
      "CopiedWindow(start=$start, slots=$slots, prefix=$prefix, packets=$packets, gridEnd=$gridEnd, video=$video, " +
        "audio=$audio, duration=$duration, adjusted=$adjusted, " +
        "slotsCovered=$slotsCovered, " +
        samples.entries.joinToString { (kind, it) -> "$kind${it.size}" } + ")"
  }

  /**
   * One sample as the container stores it.
   *
   * @property time When the sample is presented.
   * @property digest A SHA-256 of the sample's encoded bytes, so two samples compare by content.
   */
  private data class Sample(
    val time: Duration,
    val digest: String,
  )

  // The source's video samples in the order the container stores them, which is decode order.
  private fun packetsInDecodeOrder(file: File): List<CopyPacket> {
    val extractor = MediaExtractorCompat()
    try {
      extractor.setDataSource(Uri.fromFile(file), 0L)
      extractor.selectTrack(extractor.trackOf(VIDEO))
      return buildList {
        while (extractor.sampleTime >= 0) {
          add(
            CopyPacket(
              extractor.sampleTime.microseconds,
              (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0,
            ),
          )
          extractor.advance()
        }
      }
    } finally {
      extractor.release()
    }
  }

  // Every sample of the first track of this kind in the file, in the order the container stores them.
  private fun samplesInDecodeOrder(
    file: File,
    kind: String,
  ): List<Sample> {
    val extractor = MediaExtractorCompat()
    try {
      extractor.setDataSource(Uri.fromFile(file), 0L)
      extractor.selectTrack(extractor.trackOf(kind))
      val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
      // A sample time can sit before zero, so the read stops when the extractor runs out rather than on a time.
      return buildList {
        do {
          val size = extractor.readSampleData(buffer, 0)
          if (size < 0) break
          val bytes = ByteArray(size).also { buffer.get(it, 0, size) }
          add(Sample(extractor.sampleTime.microseconds, bytes.sha256()))
          buffer.clear()
        } while (extractor.advance())
      }
    } finally {
      extractor.release()
    }
  }

  private fun File.holds(kind: String): Boolean {
    val extractor = MediaExtractorCompat()
    try {
      extractor.setDataSource(Uri.fromFile(this), 0L)
      return (0 until extractor.trackCount).any {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true
      }
    } finally {
      extractor.release()
    }
  }

  private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

  private fun MediaExtractorCompat.trackOf(kind: String): Int =
    (0 until trackCount).first { getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true }

  private companion object {
    const val CLIP_LONG = "android_export_long.mp4"

    const val VIDEO = "video/"
    const val AUDIO = "audio/"

    // How many samples one AAC access unit carries, a constant of the codec.
    const val AAC_FRAME_SAMPLES = 1_024

    // Comfortably more than one encoded sample of the fixture.
    const val SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024

    // Thirty slots close on a whole reorder group, and thirty one close with the frame on the last of them decoding
    // behind two references presented past it.
    val WHOLE_GROUP_END = 1_010.milliseconds
    val SPLIT_GROUP_END = 1_040.milliseconds

    // From under one audio access unit, where the copy holds only priming, to two slots, where its last frame decodes
    // behind references presented past the grid. The two in the middle sit on one slot with one unit to hear.
    val SHORT_ENDS = listOf(21.milliseconds, 22.milliseconds, 40.milliseconds, 67.milliseconds)

    // The coarsest a container records a track's length to, since a movie's own timescale can be 10 kHz.
    val CONTAINER_TICK = 1.milliseconds

    // Half a second past one of CLIP_LONG's once a second sync samples, so the cut snaps back to it, and ending before
    // the source does.
    val SNAPPED_START = 4_500.milliseconds
    val SNAPPED_END = 7_250.milliseconds

    // Comfortably past the half second SNAPPED_START leaves to reach a sync sample.
    val SNAP_WITHIN = 1.seconds
  }
}
