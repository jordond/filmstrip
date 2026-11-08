package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How many frames a copy that ends before its source does writes, against the count the shared prefix gives over the
 * source's own samples.
 *
 * The long fixture reorders two B-frames behind each reference, so a grid can end on a whole reorder group or inside
 * one, and only the second writes references presented past it. Both the source and the written file are read with an
 * asset reader handing back samples in decode order, which is independent of the cursor the backend counts with.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleCopyGridMediaTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a copy ending on a whole reorder group writes exactly the grid`() =
    runTest(timeout = TIMEOUT) {
      val written = copiedWindow(WHOLE_GROUP_END)

      withClue("the copy wrote $written") {
        written.packets shouldBe written.prefix
        written.packets shouldBe written.slots
        written.slotsCovered shouldBe (0 until written.slots).toList()
      }
    }

  @Test
  fun `a copy ending inside a reorder group writes the shared prefix`() =
    runTest(timeout = TIMEOUT) {
      val written = copiedWindow(SPLIT_GROUP_END)

      withClue("the window was meant to end inside a reorder group") { (written.prefix > written.slots).shouldBeTrue() }
      withClue("the copy wrote $written") {
        written.packets shouldBe written.prefix
        written.slotsCovered.take(written.slots) shouldBe (0 until written.slots).toList()
      }
    }

  // The audio is laid on the composition up to the window's own end, so it covers the grid and stops within a frame
  // of the window.
  @Test
  fun `a copy's audio covers the grid`() =
    runTest(timeout = TIMEOUT) {
      listOf(WHOLE_GROUP_END, SPLIT_GROUP_END).forEach { end ->
        val written = copiedWindow(end)

        withClue("the copy to $end wrote $written") { (written.audio >= written.gridEnd).shouldBeTrue() }
      }
    }

  private suspend fun copiedWindow(end: Duration): CopiedWindow {
    val source = fixture(LONG)
    val composition = compositionOf { clip(MediaSource.of(source)) { trim(TimeRange.of(Duration.ZERO, end)) } }
    val negotiated = resolved(composition)
    withClue("an untouched window from the source's start is meant to copy") {
      negotiated.path shouldBe ExportPath.Copy
    }

    val output = NSTemporaryDirectory() + "filmstrip-apple-copy-grid.mp4"
    val finished =
      withContext(Dispatchers.Default) {
        filmstrip.export(composition, ExportSpec(), MediaSink.of(output)).toList().last()
      }
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()

    val rate = negotiated.output.frameRate
    val presented = readerPacketsOf(trackOf(output, AVMediaTypeVideo)).map { it.pts }
    return CopiedWindow(
      slots = negotiated.videoSlots.toInt(),
      prefix =
        negotiated
          .copyPrefixOf(
            readerPacketsOf(trackOf(source, AVMediaTypeVideo)),
          ).shouldBeInstanceOf<CopyPrefix.Packets>()
          .count,
      packets = presented.size,
      gridEnd = outputSlotTime(negotiated.videoSlots, rate),
      audio = CMTimeRangeGetEnd(trackOf(output, AVMediaTypeAudio).timeRange).toDuration(),
      slotsCovered = presented.sorted().map { outputSlotOf(it, rate).toInt() },
    ).also { NSFileManager.defaultManager.removeItemAtPath(output, error = null) }
  }

  /**
   * What a copy wrote, read off the container, against what the plan's grid holds.
   *
   * @property slots How many slots the plan's grid holds.
   * @property prefix How many packets the shared prefix gives over the source's samples.
   * @property packets How many video samples the file holds.
   * @property gridEnd Where the grid's last slot ends.
   * @property audio Where the audio track ends.
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

  private suspend fun resolved(composition: EditComposition) =
    withContext(Dispatchers.Default) {
      val engine =
        avFoundationExportEngine(ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build())
      when (val result = engine.resolve(composition, ExportSpec())) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private fun trackOf(
    path: String,
    mediaType: String?,
  ): AVAssetTrack =
    withClue("$path has no $mediaType track") {
      (
        AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
          .tracksWithMediaType(mediaType)
          .firstOrNull() as? AVAssetTrack
      ).shouldNotBeNull()
    }

  private companion object {
    const val LONG = "apple_export_long.mp4"

    val TIMEOUT = 2.seconds * 60

    // Thirty slots close on a whole reorder group, and thirty one close with the frame on the last of them decoding
    // behind two references presented past it.
    val WHOLE_GROUP_END = 1_010.milliseconds
    val SPLIT_GROUP_END = 1_040.milliseconds
  }
}
