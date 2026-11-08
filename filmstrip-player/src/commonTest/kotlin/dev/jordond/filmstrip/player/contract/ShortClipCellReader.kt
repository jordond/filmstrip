package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import dev.jordond.filmstrip.transform.internal.videoSlotsOf
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Reads one short clip cell on a backend, the file, the preview and how the two agree, each slot against the source
 * frame [nearestSourceFrame] picks for its [sourceTimeOfSlot].
 *
 * The hooks here are what a backend says about its own file and preview. [ShortClipContractTest] holds the cells.
 */
abstract class ShortClipCellReader(
  backend: ClipParityBackend,
) : ClipParityBackend by backend {
  /**
   * How many video samples the file [composition] exports to holds.
   */
  protected abstract suspend fun writtenFrameCount(composition: EditComposition): Long

  /**
   * A source running at [rate], with audio and at least a [MOVING_RUN] of picture at the fixture's rate.
   */
  protected abstract suspend fun sourceAt(rate: Int): MediaSource

  /**
   * Whether every slot of a cell is read through the preview, for a backend whose read opens its own window on the
   * source, which can land elsewhere than a read forward from the head.
   */
  protected open val readsEveryWindow: Boolean get() = false

  /**
   * How far past a source frame's own time a read of a plain clip lands on that frame at [rate].
   *
   * A quarter of a frame, where picking the frame at or before a time and picking the nearest both read it. It names
   * that backend's limit where it overrides this.
   */
  protected open fun frameReadLead(rate: Int): Duration = 1.seconds / rate / 4

  /**
   * Whether the file holds the primary's last frame over the slots a longer track runs past it, and the preview with
   * it.
   *
   * A backend that writes no frame there overrides this and names that limit, and its file then holds the primary's
   * own slots alone.
   */
  protected open val writesHeldTail: Boolean get() = true

  /**
   * Whether the file writes a sample on every slot of a clip, so a frame its slots show twice is written twice, as a
   * slowed clip or a slower source shows them.
   *
   * A backend that writes each frame once overrides this and names that limit, and its file then holds one sample for
   * each frame such a clip shows.
   */
  protected open val writesEverySlowedSlot: Boolean get() = true

  /**
   * Runs the cell [build] makes from [source], on a grid of [rate] from a source running at [sourceRate].
   */
  internal fun assertCell(
    rate: Int = frameRate,
    source: suspend () -> MediaSource = { movingSource() },
    sourceRate: Int = rate,
    build: suspend (MediaSource) -> Cell,
  ) = contractTest(SHORT_CLIP_BUDGET) { scope ->
    val media = source()
    assertRunsAt(media, sourceRate)
    val cell = build(media)
    val references = referencesOf(scope, media, cell.reads.map { it.sourceFrame }.toSet(), sourceRate)
    val shown = mutableMapOf<Long, TestFrame>()
    runSides(
      "file" to { assertFile(media, cell, references, rate) },
      "preview" to { assertPreview(scope, cell, references, shown, rate) },
      "agreement" to { assertAgreement(cell.edit, shown, rate) },
    )
  }

  /**
   * The read of [slot] in [placed], the frame of a source at [sourceRate] nearest that slot's source time on a grid of
   * [rate].
   */
  internal fun readOf(
    placed: PlacedClip,
    slot: Long,
    rate: Int = frameRate,
    sourceRate: Int = rate,
  ): Read =
    Read(
      slot,
      checkNotNull(nearestSourceFrame(sourceFrameTimes(sourceRate), sourceTimeAt(placed, slot, rate))).toLong(),
    )

  /**
   * The source time [slot] shows in [placed] on a grid of [rate].
   */
  internal fun sourceTimeAt(
    placed: PlacedClip,
    slot: Long,
    rate: Int = frameRate,
  ): Duration = sourceTimeOfSlot(placed.cut.trim.start, placed.span.start, placed.cut.speed, slot, rate)

  /**
   * The read of the last slot of the first clip of [run] and of the first slot of its last.
   */
  internal fun neighbourReads(run: List<PlacedClip>): List<Read> =
    listOf(
      readOf(run.first(), outputSlotsOf(run.first().span, frameRate).last),
      readOf(run.last(), outputSlotsOf(run.last().span, frameRate).first),
    )

  /**
   * How many frames the file of [run] holds, every slot less those of [reads] that show the frame the slot before them
   * did, on a backend that writes such a frame once.
   */
  internal fun writtenFramesOf(
    run: List<PlacedClip>,
    reads: List<Read>,
  ): Long {
    val repeated = reads.zipWithNext().count { (before, read) -> before.sourceFrame == read.sourceFrame }
    val unwritten = if (writesEverySlowedSlot) 0 else repeated
    return videoSlotsOf(checkNotNull(run.last().span.endExclusive), frameRate) - unwritten
  }

  private suspend fun assertRunsAt(
    source: MediaSource,
    rate: Int,
  ) {
    val probed =
      filmstrip
        .probe(source)
        .shouldBeInstanceOf<ProbeResult.Success>()
        .info.video
    withClue("the source does not run at $rate") { probed?.frameRate?.roundToInt() shouldBe rate }
  }

  private suspend fun assertFile(
    source: MediaSource,
    cell: Cell,
    references: Map<Long, TestFrame>,
    rate: Int,
  ) {
    val duration = checkNotNull(cell.edit.duration)
    withClue("the frames in the file") {
      writtenFrameCount(cell.edit) shouldBe (cell.writtenFrames ?: videoSlotsOf(duration, rate))
    }
    cell.reads.forEach { read ->
      val frame = exportFrame(cell.edit, outputSlotTime(read.slot, rate))
      assertSameFrame(references.getValue(read.sourceFrame), frame, read.slot, "the file at slot ${read.slot}")
    }
    cell.writtenAtSpeed?.let { speed -> assertWrittenLength(source, cell.edit, duration, speed) }
  }

  private suspend fun assertPreview(
    scope: CoroutineScope,
    cell: Cell,
    references: Map<Long, TestFrame>,
    shown: MutableMap<Long, TestFrame>,
    rate: Int,
  ) {
    val duration = checkNotNull(cell.edit.duration)
    val windows = if (readsEveryWindow) (0 until videoSlotsOf(duration, rate)).toList() else emptyList()
    val landed = mutableMapOf<Long, Duration>()
    val engine = createEngine(scope)
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(cell.edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit to be presentable") { recorder.lastState.hasComposition }
      recorder.lastState.duration shouldBe duration
      (cell.reads.map { it.slot } + windows).toSet().forEach { slot ->
        val frame = engine.readback.awaitFrame(outputSlotTime(slot, rate))
        shown[slot] = frame.asTestFrame()
        landed[slot] = frame.presentationTime
      }
    }
    cell.reads.forEach { read ->
      val frame = shown.getValue(read.slot)
      val where = "the preview at slot ${read.slot}, landed at ${landed.getValue(read.slot)}"
      val closest = closestOf(references, frame)
      assertSameFrame(references.getValue(read.sourceFrame), frame, read.slot, "$where and $closest,")
    }
  }

  // Which of the cell's source frames [frame] reads closest to, so a wrong frame is named.
  private fun closestOf(
    references: Map<Long, TestFrame>,
    frame: TestFrame,
  ): String {
    val (source, comparison) =
      references
        .map { (source, reference) -> source to compareFrames(comparedPart(reference), comparedPart(frame)) }
        .maxBy { (_, comparison) -> comparison.psnrDb }
    return "closest to source frame $source of ${references.keys.sorted()} at $comparison"
  }

  private suspend fun assertAgreement(
    edit: EditComposition,
    shown: Map<Long, TestFrame>,
    rate: Int,
  ) {
    shown.forEach { (slot, frame) ->
      val written = exportFrame(edit, outputSlotTime(slot, rate))
      assertSameFrame(written, frame, slot, "the preview and the file at slot $slot")
    }
  }

  private fun assertSameFrame(
    expected: TestFrame,
    actual: TestFrame,
    slot: Long,
    what: String,
  ) = assertFramesSimilar(
    expected = comparedPart(expected),
    actual = comparedPart(actual),
    minPsnrDb = minPsnrDb,
    minSsim = minSsimAt(slot),
    message = "$what is not the frame it should be",
  )

  // Each source frame as a plain clip of the source shows it, read through [frameReadLead].
  private suspend fun referencesOf(
    scope: CoroutineScope,
    source: MediaSource,
    frames: Set<Long>,
    rate: Int,
  ): Map<Long, TestFrame> {
    val run = TimeRange.of(Duration.ZERO, outputSlotTime(frames.max() + 2, rate))
    val plain = EditComposition(listOf(Track(listOf(Clip(source, run)))))
    val engine = createEngine(scope)
    val references = mutableMapOf<Long, TestFrame>()
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(plain).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the plain clip to be presentable") { recorder.lastState.hasComposition }
      frames.forEach { frame ->
        val at = outputSlotTime(frame, rate) + frameReadLead(rate)
        references[frame] = engine.readback.awaitFrame(at).asTestFrame()
      }
    }
    return references
  }
}

/**
 * How long one short clip cell may run, every side of it included.
 */
internal val SHORT_CLIP_BUDGET = 3.minutes

/**
 * Every frame of a source at [rate] that holds a [MOVING_RUN] of picture, on its own time.
 */
internal fun sourceFrameTimes(rate: Int): List<Duration> {
  val frame = 1.seconds / rate
  return (0..(checkNotNull(MOVING_RUN.duration) / frame).toInt()).map { frame * it }
}
