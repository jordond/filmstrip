package dev.jordond.filmstrip.player

import android.media.MediaFormat
import android.net.Uri
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailSource
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.test.videoSampleTimesOf
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.transform.internal.seekTolerance
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A clip trimmed before its source ends is drawn to its last slot by everything that reads it.
 *
 * The fixture stores its frames out of the order they are shown in, so the first sample at or past a trim's end sits
 * in the file ahead of frames shown before that end. The moving source is this backend's own export, which stores its
 * frames in the order they are shown.
 */
class AndroidClipEndReadMediaTest {
  @Test
  fun aReadbackShowsEverySlotOfAClipWhoseFramesAreStoredOutOfOrder() =
    contractTest(BODY_BUDGET) { scope ->
      val stored = storedSampleTimesOf(androidFixtureClip())
      ENDS.forEach { end ->
        withClue("the fixture stores no frame shown before $end behind the first one at or past it") {
          (stored.indexOfFirst { it >= end } < stored.indexOfLast { it < end }) shouldBe true
        }
      }
      val misses =
        ENDS.flatMap { end ->
          val edit = reorderedEdit(end)
          readbackMisses(AndroidClipParity.createEngine(scope), edit, slotsOf(edit))
        }
      misses.shouldAllBeRead()
    }

  @Test
  fun aReadbackShowsEverySlotOfAClipWhoseFramesAreStoredInOrder() =
    contractTest(BODY_BUDGET) { scope ->
      val moving = AndroidClipParity.movingSource()
      withClue("the moving source stores its frames out of the order they are shown in") {
        storedSampleTimesOf(moving).isAscending() shouldBe true
      }
      val edit = Composition(listOf(Track(listOf(Clip(moving, TimeRange.of(Duration.ZERO, ENDS.first()))))))
      readbackMisses(AndroidClipParity.createEngine(scope), edit, slotsOf(edit)).shouldAllBeRead()
    }

  @Test
  fun aPreciseThumbnailShowsTheLastSlotsOfAClipWhoseFramesAreStoredOutOfOrder() =
    contractTest(BODY_BUDGET) { scope ->
      val source = Media3ThumbnailSource(scope, contractContext(), Media3ThumbnailPlanner(CONTRACT_COMPONENTS))
      val misses = mutableListOf<String>()
      for (end in ENDS) {
        val edit = reorderedEdit(end)
        for (slot in slotsOf(edit).takeLast(LAST_SLOTS)) {
          val at = outputSlotTime(slot, FIXTURE_FRAME_RATE)
          val thumbnail =
            source.awaitThumbnail(ThumbnailRequest(edit, at, FIXTURE_FRAME.height, REVISION, precise = true))
          try {
            misses += missesAt(edit, slot, thumbnail.presentationTime, thumbnail.drawn())
          } finally {
            thumbnail.image.close()
          }
        }
      }
      misses.shouldAllBeRead()
    }

  @Test
  fun aRelaxedThumbnailOfTheLastSlotShowsAFrameOfAClipWhoseFramesAreStoredOutOfOrder() =
    contractTest(BODY_BUDGET) { scope ->
      val source = Media3ThumbnailSource(scope, contractContext(), Media3ThumbnailPlanner(CONTRACT_COMPONENTS))
      val edit = reorderedEdit(ENDS.last())
      val last = slotsOf(edit).last()
      val at = outputSlotTime(last, FIXTURE_FRAME_RATE)
      val thumbnail = source.awaitThumbnail(ThumbnailRequest(edit, at, FIXTURE_FRAME.height, REVISION, precise = false))
      try {
        val landed = outputSlotOf(thumbnail.presentationTime, FIXTURE_FRAME_RATE)
        withClue("asked for slot $last at $at, it landed on slot $landed at ${thumbnail.presentationTime}") {
          landed shouldBeLessThanOrEqual last
          (at - thumbnail.presentationTime).absoluteValue shouldBeLessThanOrEqualTo
            seekTolerance(precise = false, FIXTURE_FRAME_STEP, FIXTURE_SYNC_INTERVAL)
        }
        missesAt(edit, landed, thumbnail.presentationTime, thumbnail.drawn()).shouldAllBeRead()
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun anExportWritesEverySlotOfAClipWhoseFramesAreStoredOutOfOrder() =
    contractTest(BODY_BUDGET) {
      for (end in ENDS) {
        val edit = reorderedEdit(end)
        val written = videoSampleTimesOf(androidExportedFile(edit))
        withClue("the file of the clip ending at $end holds frames at $written") {
          written.map { outputSlotOf(it, FIXTURE_FRAME_RATE) } shouldBe slotsOf(edit)
        }
      }
    }

  /**
   * What is wrong with the preview's readback of [edit] at each of [slots], nothing when every one reads its own slot
   * and the frame the file holds there.
   */
  private suspend fun readbackMisses(
    engine: PlayerEngine,
    edit: Composition,
    slots: List<Long>,
  ): List<String> {
    val misses = mutableListOf<String>()
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit to be presentable") { recorder.lastState.hasComposition }
      for (slot in slots) {
        val shown = engine.readback.awaitFrame(outputSlotTime(slot, FIXTURE_FRAME_RATE))
        misses += missesAt(edit, slot, shown.presentationTime, shown.asTestFrame())
      }
    }
    return misses
  }

  private suspend fun missesAt(
    edit: Composition,
    slot: Long,
    landed: Duration,
    drawn: TestFrame,
  ): List<String> {
    val length = checkNotNull(edit.duration)
    val exported = AndroidClipParity.exportFrame(edit, outputSlotTime(slot, FIXTURE_FRAME_RATE))
    val scored = compareFrames(AndroidClipParity.comparedPart(exported), AndroidClipParity.comparedPart(drawn))
    return buildList {
      val landedSlot = outputSlotOf(landed, FIXTURE_FRAME_RATE)
      if (landedSlot != slot) add("slot $slot of $length landed on slot $landedSlot at $landed")
      if (scored.psnrDb < MIN_PSNR_DB || scored.ssim < encodedMinSsimAt(slot, MIN_SSIM)) {
        add("slot $slot of $length is not the frame the file shows there: $scored")
      }
    }
  }

  private fun List<String>.shouldAllBeRead() = withClue(joinToString("\n")) { shouldBeEmpty() }

  private fun reorderedEdit(end: Duration): Composition =
    Composition(
      listOf(
        Track(listOf(Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, end), effects = listOf(FIXTURE_PAN)))),
      ),
    )

  private fun slotsOf(edit: Composition): List<Long> =
    (0 until outputSlotOf(checkNotNull(edit.duration), FIXTURE_FRAME_RATE)).toList()

  /**
   * The time of every video sample of [source] in the order its file stores them, which is the order a decoder is
   * handed them in.
   */
  private fun storedSampleTimesOf(source: MediaSource): List<Duration> {
    val file = File(source.shouldBeInstanceOf<MediaSource.Path>().path)
    val extractor = MediaExtractorCompat().apply { setDataSource(Uri.fromFile(file), 0L) }
    try {
      val video =
        (0 until extractor.trackCount).first {
          extractor
            .getTrackFormat(it)
            .getString(MediaFormat.KEY_MIME)
            .orEmpty()
            .startsWith("video/")
        }
      extractor.selectTrack(video)
      return buildList {
        while (extractor.sampleTrackIndex >= 0) {
          add(extractor.sampleTime.microseconds)
          extractor.advance()
        }
      }
    } finally {
      extractor.release()
    }
  }

  private fun ThumbnailResult.Success.drawn(): TestFrame =
    TestFrame(image.toRgba8888(), Size(image.widthPx, image.heightPx))

  private fun List<Duration>.isAscending(): Boolean = zipWithNext().all { (earlier, later) -> earlier < later }

  private suspend fun ThumbnailSource.awaitThumbnail(request: ThumbnailRequest): ThumbnailResult.Success =
    suspendCancellableCoroutine { continuation ->
      val handle =
        requestThumbnail(request) { result ->
          if (!continuation.isActive) return@requestThumbnail
          when (result) {
            is ThumbnailResult.Success -> continuation.resume(result)
            is ThumbnailResult.Failure -> continuation.resume(fail("the thumbnail failed: ${result.error.message}"))
          }
        }
      continuation.invokeOnCancellation { handle.cancel() }
    }

  private companion object {
    // Each end falls where the fixture stores a later frame ahead of the last ones shown before it.
    val ENDS: List<Duration> = listOf(500.milliseconds, 1_500.milliseconds)

    // One more than the most slots the fixture stores behind the first sample past either end.
    const val LAST_SLOTS = 4

    const val REVISION = 1L

    // The floors a pan over a clip holds against its export, as AndroidPausedSurfaceMediaTest holds a surface to it.
    const val MIN_PSNR_DB = 32.0
    const val MIN_SSIM = 0.975

    val BODY_BUDGET = 6.minutes
  }
}
