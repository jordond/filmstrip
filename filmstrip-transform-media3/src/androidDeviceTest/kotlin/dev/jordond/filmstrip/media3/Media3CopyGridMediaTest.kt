package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How many frames a copy that ends before its source does writes, against the count the shared prefix gives over the
 * source's own packets.
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

  // One media3 item carries one clipping end for both its tracks, so the audio of a copy stops with its video, on the
  // grid rather than on the window's own end. Copied audio ends on a whole access unit, and which side of the grid end
  // that unit lands on is media3's own call, measured on both sides across API levels, so the bound is one unit.
  @Test
  fun aCopysAudioEndsWithinOneAccessUnitOfTheGrid() =
    runTest(timeout = TIMEOUT) {
      val unit =
        AAC_FRAME_SAMPLES.seconds /
          probe
            .infoOf(probe.fixture(CLIP_LONG))
            .audio
            .shouldNotBeNull()
            .sampleRate
      listOf(WHOLE_GROUP_END, SPLIT_GROUP_END).forEach { end ->
        val written = copiedWindow(end)

        withClue("the copy to $end wrote $written, against a $unit access unit") {
          ((written.audio - written.gridEnd).absoluteValue < unit) shouldBe true
        }
      }
    }

  private suspend fun copiedWindow(end: Duration): CopiedWindow {
    val sourceFile = probe.fixtureFile(CLIP_LONG)
    val source = MediaSource.of(sourceFile.path)
    val composition = EditComposition(listOf(Track(listOf(Clip(source, trim = TimeRange.of(Duration.ZERO, end))))))
    val negotiated =
      when (val result = engine.resolve(composition, ExportSpec())) {
        is ResolveResult.Resolved -> result.composition
        is ResolveResult.Refused -> throw AssertionError("the copy would not lower: ${result.error.message}")
      }
    val plan = probe.plan(composition, ExportSpec())
    withClue("an untouched window from the source's start is meant to copy") { plan.path shouldBe ExportPath.Copy }

    val file = probe.export(plan)
    val rate = negotiated.output.frameRate
    val presented = videoSampleTimes(file)
    return CopiedWindow(
      slots = negotiated.videoSlots.toInt(),
      prefix = negotiated.copyPrefixOf(packetsInDecodeOrder(sourceFile)).shouldBeInstanceOf<CopyPrefix.Packets>().count,
      packets = presented.size,
      gridEnd = outputSlotTime(negotiated.videoSlots, rate),
      audio = audioTrackLength(file),
      slotsCovered = presented.map { outputSlotOf(it, rate).toInt() },
    ).also { file.delete() }
  }

  /**
   * What a copy wrote, read off the container, against what the plan's grid holds.
   *
   * @property slots How many slots the plan's grid holds.
   * @property prefix How many packets the shared prefix gives over the source's packets.
   * @property packets How many video samples the file holds.
   * @property gridEnd Where the grid's last slot ends.
   * @property audio How long the audio track runs.
   * @property slotsCovered The slot each written frame is presented on, in presentation order.
   */
  private data class CopiedWindow(
    val slots: Int,
    val prefix: Int,
    val packets: Int,
    val gridEnd: Duration,
    val audio: Duration,
    val slotsCovered: List<Int>,
  )

  // The source's video samples in the order the container stores them, which is decode order.
  private fun packetsInDecodeOrder(file: File): List<CopyPacket> {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.path)
      val track =
        (0 until extractor.trackCount).first {
          extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
      extractor.selectTrack(track)
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

  private companion object {
    const val CLIP_LONG = "android_export_long.mp4"

    // How many samples one AAC access unit carries, a constant of the codec.
    const val AAC_FRAME_SAMPLES = 1_024

    // Thirty slots close on a whole reorder group, and thirty one close with the frame on the last of them decoding
    // behind two references presented past it.
    val WHOLE_GROUP_END = 1_010.milliseconds
    val SPLIT_GROUP_END = 1_040.milliseconds
  }
}
