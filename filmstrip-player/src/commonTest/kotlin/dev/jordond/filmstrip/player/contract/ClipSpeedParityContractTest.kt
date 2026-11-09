package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
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
abstract class ClipSpeedParityContractTest(
  backend: ClipParityBackend,
) : ClipParityBackend by backend {
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
    val length = outputLengthOf(checkNotNull(BASELINE_TRIM.duration), speed)
    val edit = editOf(source, BASELINE_TRIM, speed)
    edit.duration shouldBe length
    val bare = Composition(listOf(Track(listOf(Clip(source, BASELINE_TRIM, speed = speed)))))
    val plain = Composition(listOf(Track(listOf(Clip(source, BASELINE_TRIM)))))

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

      for (shown in bareReads) assertMatchesExport(bare, BASELINE_TRIM, shown, speed)
      for (shown in listOf(
        shownBefore,
        shownInRamp,
        shownMiddle,
      )) {
        assertMatchesExport(edit, BASELINE_TRIM, shown, speed)
      }
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
    edit: Composition,
    trim: TimeRange,
    shown: ReadbackFrame,
    speed: Double,
  ) {
    val frame = comparedPart(shown.asTestFrame())
    val sourceFrame = 1.seconds / frameRate
    val lastSlot = outputSlotTime(outputSlotOf(checkNotNull(edit.duration), frameRate) - 1, frameRate)
    val halves = if (landsOnAFrame(shown.presentationTime, trim, speed)) 2 else 0
    val (position, exported) =
      (-halves..halves)
        .map { half -> shown.presentationTime + outputLengthOf(sourceFrame * half.toDouble() / 2, speed) }
        .map { it.coerceIn(Duration.ZERO, lastSlot) }
        .map { it to comparedPart(exportFrame(edit, it)) }
        .maxBy { (_, read) -> compareFrames(read, frame).psnrDb }
    assertFramesSimilar(
      expected = exported,
      actual = frame,
      minPsnrDb = minPsnrDb,
      minSsim = minSsimAt(outputSlotOf(position, frameRate)),
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

  private fun slotAt(position: Duration): Duration = outputSlotTime(outputSlotOf(position, frameRate), frameRate)

  private fun editOf(
    source: MediaSource,
    trim: TimeRange,
    speed: Double,
  ): Composition {
    val length = outputLengthOf(checkNotNull(trim.duration), speed)
    val caption =
      TextOverlay(
        text = CAPTION,
        anchor = Anchor.Center,
        visibleDuring = TimeRange.of(length / 4, length * 3 / 4),
        animation = fade(inDuration = length / 8),
      )
    return Composition(
      tracks = listOf(Track(listOf(Clip(source, trim, speed = speed)), fadeIn = length / 4)),
      effects = listOf(caption),
    )
  }

  private companion object {
    val SLOW_END_TRIM = TimeRange.of(200.milliseconds, 325.milliseconds)

    // At 32 a slot steps a whole number of source frames, so a trim opening on a frame reads every slot on a frame's
    // own boundary, where the rounding of a time decides. A quarter of a frame in reads clear of it.
    val FAST_END_TRIM = TimeRange.of(1.seconds / 120, 1_500.milliseconds)

    const val CAPTION = "Speed"
  }
}
