package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.DEFAULT_MIN_PSNR_DB
import dev.jordond.filmstrip.test.DEFAULT_MIN_SSIM
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One edit at a clip speed on every backend: a trimmed clip with its audio on, a track fade and a fading caption with
 * its own visible range.
 *
 * Each backend's preview reports the shared output length and shows the source frame the shared offset reaches, the
 * caption keeps to its range in output time, the preview and the export read as the same frame, and the written file
 * runs the shared output length. The two ends of the range carry the same edit through the export and a frame read.
 *
 * The source moves, so a frame taken from the wrong place in it reads differently. A backend whose fixture holds one
 * still picture uses [movingSource] to write one first.
 *
 * Camel case test names, for the reason [PlayerEngineContractTest] gives.
 */
abstract class ClipSpeedParityContractTest {
  /**
   * Builds a fresh engine holding no composition.
   *
   * Called once per test. The suite disposes the engine afterwards.
   *
   * @param scope The dispatcher the suite drives the engine from. Confine platform callbacks to it.
   */
  protected abstract fun createEngine(scope: CoroutineScope): PlayerEngine

  /**
   * The registered backend, which probes what an export wrote.
   */
  protected abstract val filmstrip: Filmstrip

  /**
   * This backend's fixture clip, with audio.
   */
  protected abstract val fixtureClip: MediaSource

  /**
   * The rate the fixture plays at, which is the grid both sides of a comparison read on.
   */
  protected abstract val frameRate: Int

  /**
   * Writes [composition] through a real export and hands back the file as a source.
   */
  protected abstract suspend fun exportedSource(composition: EditComposition): MediaSource

  /**
   * The frame [composition] exports at [position], as tightly packed RGBA_8888.
   */
  protected abstract suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame

  /**
   * How close a preview frame and its export have to read, loosened only for what this backend's export path costs.
   */
  protected open val minPsnrDb: Double get() = DEFAULT_MIN_PSNR_DB

  protected open val minSsim: Double get() = DEFAULT_MIN_SSIM

  /**
   * How close to a source frame's own time a preview read may land and show the frame either side of it, for a backend
   * that cannot read that finely. It names that backend's limit where it overrides this.
   */
  protected open val previewReadSlack: Duration get() = Duration.ZERO

  /**
   * What both sides of a preview and export comparison are read through, the whole frame by default. It names that
   * backend's limit where it overrides this.
   */
  protected open fun comparedPart(frame: TestFrame): TestFrame = frame

  /**
   * How much further than a slot and an access unit past the shared length this backend's file may run at [speed]. It
   * names that backend's limit where it overrides this.
   */
  protected open fun writtenLengthSlack(speed: Double): Duration = Duration.ZERO

  /**
   * A source whose picture moves from frame to frame, with the fixture's audio.
   */
  protected open suspend fun movingSource(): MediaSource =
    exportedSource(
      EditComposition(listOf(Track(listOf(Clip(fixtureClip, SOURCE, effects = listOf(PAN)))))),
    )

  @Test
  fun aClipAtSpeedPreviewsTheWayItExportsInTheMiddleOfTheRange() =
    contractTest { scope ->
      val moving = movingSource()
      listOf(0.7, 1.5).forEach { speed -> assertMiddleSpeed(scope, moving, speed) }
    }

  @Test
  fun aClipAtTheSlowEndOfTheRangeHoldsItsLengthAndItsPreview() =
    contractTest { scope ->
      assertRangeEnd(scope, movingSource(), CLIP_SPEED_RANGE.start, SLOW_END_TRIM)
    }

  @Test
  fun aClipAtTheFastEndOfTheRangeHoldsItsLengthAndItsPreview() =
    contractTest { scope ->
      assertRangeEnd(scope, movingSource(), CLIP_SPEED_RANGE.endInclusive, FAST_END_TRIM)
    }

  private suspend fun assertMiddleSpeed(
    scope: CoroutineScope,
    source: MediaSource,
    speed: Double,
  ) {
    val length = outputLengthOf(checkNotNull(TRIM.duration), speed)
    val edit = editOf(source, TRIM, speed)
    edit.duration shouldBe length
    val bare = EditComposition(listOf(Track(listOf(Clip(source, TRIM, speed = speed)))))
    val plain = EditComposition(listOf(Track(listOf(Clip(source, TRIM)))))

    // Before the caption opens, inside its fade, and on its plateau. Only an inside reading can tell output time from
    // source time, and the ramp is where a fade read on the wrong clock shows.
    val before = slotAt(length / 8)
    val inRamp = slotAt(length / 4 + length / 16)
    val middle = slotAt(length / 2)

    val engine = createEngine(scope)
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(plain).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the plain clip to be presentable") { recorder.lastState.hasComposition }
      val offset = sourceOffsetAt(middle, speed) / (1.seconds / frameRate)
      val reached =
        listOf(floor(offset), ceil(offset)).map { frame ->
          engine.readback
            .awaitFrame(outputSlotTime(frame.toLong(), frameRate))
            .asTestFrame()
        }
      val unmoved =
        engine.readback
          .awaitFrame(middle)
          .asTestFrame()
      assertFramesDiffer(
        reached.first(),
        unmoved,
        message = "the source barely moves between the frames apart at $speed",
      )

      engine.awaitComposition(bare).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the bare clip at $speed to be presentable") { recorder.lastState.hasComposition }
      val bareReads = listOf(engine.readback.awaitFrame(before), engine.readback.awaitFrame(middle))
      val bareBefore = bareReads.first().asTestFrame()
      val bareMiddle = bareReads.last().asTestFrame()
      // A backend picks the frame at or before a source time, or the nearest, and those are both a source frame either
      // side of the offset.
      val closest = reached.maxBy { compareFrames(it, bareMiddle).psnrDb }
      assertFramesSimilar(closest, bareMiddle, message = "the preview at $speed against the source frame it reaches")

      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit at $speed to be presentable") { recorder.lastState.hasComposition }
      recorder.lastState.duration shouldBe length

      val shownBefore = engine.readback.awaitFrame(before)
      val shownInRamp = engine.readback.awaitFrame(inRamp)
      val shownMiddle = engine.readback.awaitFrame(middle)
      assertFramesSimilar(
        bareBefore,
        shownBefore.asTestFrame(),
        message = "the caption showed before its range at $speed",
      )
      assertFramesDiffer(bareMiddle, shownMiddle.asTestFrame(), message = "the caption hid inside its range at $speed")

      for (shown in bareReads) assertMatchesExport(bare, TRIM, shown, speed)
      for (shown in listOf(shownBefore, shownInRamp, shownMiddle)) assertMatchesExport(edit, TRIM, shown, speed)
    }

    assertWrittenLength(source, edit, length, speed)
  }

  private suspend fun assertRangeEnd(
    scope: CoroutineScope,
    source: MediaSource,
    speed: Double,
    trim: TimeRange,
  ) {
    val length = outputLengthOf(checkNotNull(trim.duration), speed)
    val edit = editOf(source, trim, speed)
    edit.duration shouldBe length

    val engine = createEngine(scope)
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit at $speed to be presentable") { recorder.lastState.hasComposition }
      recorder.lastState.duration shouldBe length

      val lastSlot = outputSlotTime(outputSlotOf(length, frameRate) - 1, frameRate)
      val shown = engine.readback.awaitFrame(minOf(slotAt(length / 2), lastSlot))
      assertMatchesExport(edit, trim, shown, speed)
    }

    assertWrittenLength(source, edit, length, speed)
  }

  private suspend fun assertMatchesExport(
    edit: EditComposition,
    trim: TimeRange,
    shown: ReadbackFrame,
    speed: Double,
  ) {
    val frame = comparedPart(shown.asTestFrame())
    val sourceFrame = 1.seconds / frameRate
    val lastSlot = outputSlotTime(outputSlotOf(checkNotNull(edit.duration), frameRate) - 1, frameRate)
    val halves = if (landsOnAFrame(shown.presentationTime, trim, speed)) 2 else 0
    val exported =
      (-halves..halves)
        .map { half -> shown.presentationTime + outputLengthOf(sourceFrame * half.toDouble() / 2, speed) }
        .map { comparedPart(exportFrame(edit, it.coerceIn(Duration.ZERO, lastSlot))) }
        .maxBy { compareFrames(it, frame).psnrDb }
    assertFramesSimilar(
      expected = exported,
      actual = frame,
      minPsnrDb = minPsnrDb,
      minSsim = minSsim,
      message = "the preview and the export disagree at ${shown.presentationTime} at $speed",
    )
  }

  // Whether a read at this output time reaches within this backend's slack of a source frame's own time.
  private fun landsOnAFrame(
    at: Duration,
    trim: TimeRange,
    speed: Double,
  ): Boolean {
    if (previewReadSlack == Duration.ZERO) return false
    val sourceFrame = 1.seconds / frameRate
    val frames = (trim.start + sourceOffsetAt(at, speed)) / sourceFrame
    return outputLengthOf(sourceFrame * abs(frames - round(frames)), speed) <= previewReadSlack
  }

  // Every backend's audio encoder adds its own tail to a file, a clip at speed 1 included, so what that clip adds is
  // the baseline. Past it a file ends on a whole slot and one more access unit of audio.
  private suspend fun assertWrittenLength(
    source: MediaSource,
    edit: EditComposition,
    length: Duration,
    speed: Double,
  ) {
    val unity = EditComposition(listOf(Track(listOf(Clip(source, TRIM)))))
    val added = writtenLengthOf(unity) - checkNotNull(unity.duration)
    val written = writtenLengthOf(edit)
    withClue("the file at $speed ran $written against a shared length of $length, and speed 1 adds $added") {
      val tolerance = 1.seconds / frameRate + AAC_UNIT + writtenLengthSlack(speed)
      ((written - length - added).absoluteValue <= tolerance).shouldBeTrue()
    }
  }

  private suspend fun writtenLengthOf(composition: EditComposition): Duration =
    filmstrip
      .probe(exportedSource(composition))
      .shouldBeInstanceOf<ProbeResult.Success>()
      .info.duration

  private fun slotAt(position: Duration): Duration = outputSlotTime(outputSlotOf(position, frameRate), frameRate)

  private fun editOf(
    source: MediaSource,
    trim: TimeRange,
    speed: Double,
  ): EditComposition {
    val length = outputLengthOf(checkNotNull(trim.duration), speed)
    val caption =
      TextOverlay(
        text = CAPTION,
        anchor = Anchor.Center,
        visibleDuring = TimeRange.of(length / 4, length * 3 / 4),
        animation = fade(inDuration = length / 8),
      )
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(source, trim, speed = speed)), fadeIn = length / 4)),
      effects = listOf(caption),
    )
  }

  private companion object {
    // Every backend's fixture runs at least this long.
    val SOURCE = TimeRange.of(Duration.ZERO, 1_500.milliseconds)
    val TRIM = TimeRange.of(200.milliseconds, 1_300.milliseconds)
    val SLOW_END_TRIM = TimeRange.of(200.milliseconds, 325.milliseconds)

    // At 32 a slot steps a whole number of source frames, so a trim opening on a frame reads every slot on a frame's
    // own boundary, where the rounding of a time decides. A quarter of a frame in reads clear of it.
    val FAST_END_TRIM = TimeRange.of(1.seconds / 120, 1_500.milliseconds)

    val PAN =
      KenBurns(
        from = NormalizedRect(0f, 0f, 0.4f, 1f),
        to = NormalizedRect(0.6f, 0f, 1f, 1f),
        easing = Easing.Linear,
      )

    const val CAPTION = "Speed"
    val AAC_UNIT = 1.seconds * 1_024 / 48_000
  }
}
