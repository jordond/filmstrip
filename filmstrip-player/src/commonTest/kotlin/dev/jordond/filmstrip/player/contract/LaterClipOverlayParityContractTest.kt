package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameWithin
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An animated caption scoped to the second of two clips, seeked to on every backend.
 *
 * The preview at a seek inside the caption's fade, and at the slot after the second clip opens, reads as the same edit
 * drawn with the caption held at what [frameWithin] answers for that frame, and as the export at the same slot. The
 * caption's plate is opaque, so a fade read on the composition's clock or one restarted at the seek draws a frame the
 * held one does not match.
 *
 * Camel case test names, for the reason [PlayerEngineContractTest] gives.
 */
abstract class LaterClipOverlayParityContractTest(
  backend: ClipParityBackend,
) : ClipParityBackend by backend {
  @Test
  fun aSeekIntoASecondClipDrawsItsOverlayWhereTheSharedSamplerPutsIt() =
    contractTest { scope ->
      val fading = caption(fade(inDuration = FADE))
      val edit = editWith(fading)
      val drawnRange = fading.drawnRangeWithin(SECOND_CLIP)
      val opensOn = outputSlotOf(SECOND_CLIP.start, frameRate)
      val inFade = outputSlotTime(outputSlotOf(SECOND_CLIP.start + FADE / 2, frameRate), frameRate)
      val afterOpening = outputSlotTime(opensOn + 1, frameRate)

      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        engine.setQualityPolicy(PreviewQualityPolicy.Full)
        val shown =
          engine.framesOf(recorder, edit, "the faded caption", listOf(inFade, afterOpening))

        shown.forEach { frame ->
          val held = fading.frameWithin(drawnRange, frame.presentationTime).shouldNotBeNull().opacity
          val reference = editWith(caption(OverlayAnimation { OverlayFrame(opacity = held) }))
          val expected =
            engine.framesOf(recorder, reference, "the caption held at $held", listOf(frame.presentationTime)).single()
          assertFramesSimilar(
            expected.asTestFrame(),
            frame.asTestFrame(),
            message = "the caption at ${frame.presentationTime} is not drawn at the $held the sampler answers",
          )
        }

        // Inside the fade a clock counted from the composition's start reads the ramp as over, and a clock restarted
        // at the seek reads it as not begun.
        val middle = shown.first()
        val whole = engine.framesOf(recorder, editWith(caption(null)), "the whole caption", listOf(inFade)).single()
        val bare = engine.framesOf(recorder, editWith(null), "the bare clips", listOf(inFade)).single()
        assertFramesDiffer(whole.asTestFrame(), middle.asTestFrame(), message = "the fade was over at $inFade")
        assertFramesDiffer(bare.asTestFrame(), middle.asTestFrame(), message = "the fade had not begun at $inFade")

        shown.forEach { frame -> assertMatchesExport(edit, frame) }
      }
    }

  private suspend fun PlayerEngine.framesOf(
    recorder: ContractRecorder,
    composition: Composition,
    name: String,
    positions: List<Duration>,
  ): List<ReadbackFrame> {
    awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
    awaitContract("$name to be presentable") { recorder.lastState.hasComposition }
    return positions.map { readback.awaitFrame(it) }
  }

  // A backend that reads within [previewReadSlack] of a source frame's own time may show the frame either side of it,
  // so the export is read half a frame either way as well and the closest of them is held to the preview.
  private suspend fun assertMatchesExport(
    edit: Composition,
    shown: ReadbackFrame,
  ) {
    val frame = comparedPart(shown.asTestFrame())
    val sourceFrame = 1.seconds / frameRate
    val halves = if (previewReadSlack > Duration.ZERO) 2 else 0
    val exported: TestFrame =
      (-halves..halves)
        .map { half -> shown.presentationTime + sourceFrame * half / 2 }
        .map { comparedPart(exportFrame(edit, it)) }
        .maxBy { compareFrames(it, frame).psnrDb }
    assertFramesSimilar(
      expected = exported,
      actual = frame,
      minPsnrDb = minPsnrDb,
      minSsim = minSsimAt(outputSlotOf(shown.presentationTime, frameRate)),
      message = "the preview and the export disagree at ${shown.presentationTime}",
    )
  }

  // The fixture's opening and the run after it as two clips, with [overlay] on the second alone.
  private fun editWith(overlay: TextOverlay?): Composition =
    Composition(
      listOf(
        Track(
          listOf(
            Clip(fixtureClip, FIRST_CLIP),
            Clip(fixtureClip, SECOND_CLIP, effects = listOfNotNull(overlay)),
          ),
        ),
      ),
    )

  private fun caption(animation: OverlayAnimation?): TextOverlay =
    TextOverlay(text = CAPTION, style = PLATED, anchor = Anchor.Center, animation = animation)

  private companion object {
    val CLIP_LENGTH = 600.milliseconds

    // Each clip's trim, which is also where it sits on the composition, since the first opens it.
    val FIRST_CLIP = TimeRange.of(Duration.ZERO, CLIP_LENGTH)
    val SECOND_CLIP = TimeRange.of(CLIP_LENGTH, CLIP_LENGTH * 2)

    // Shorter than the second clip, so the middle of the ramp sits well inside it and the end of the ramp is before it
    // ends.
    val FADE = 400.milliseconds

    const val CAPTION = "Later"

    // An opaque plate, large enough that the opacity it is drawn at moves a good part of the frame.
    val PLATED = TextStyle(fontSize = 0.2f, backgroundColor = 0xFF000000.toInt())
  }
}
