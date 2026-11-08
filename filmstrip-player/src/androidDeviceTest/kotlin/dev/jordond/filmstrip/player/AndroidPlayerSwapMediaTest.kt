package dev.jordond.filmstrip.player

import android.os.Handler
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.cutInTwo
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the media3 engine carries onto the player it builds for a different video graph.
 *
 * Only the primary track of a planned edit carries video today, so the second video sequence is laid in by the planner
 * here, on the plan the engine is handed.
 */
@ExperimentalApi
class AndroidPlayerSwapMediaTest {
  @Test
  fun aPlayerBuiltForASecondVideoSequenceTakesTheTransportAndTheVolume() =
    contractTest { scope ->
      val plain = androidFixtureComposition()
      val layered = plain.cutInTwo(FIXTURE_FRAME_RATE)
      val subject = AndroidEngineUnderTest(scope, SecondVideoSequencePlanner(layered))
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(plain).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val standing = subject.engine.nativePlayer

        subject.engine.setVolume(VOLUME)
        subject.engine.play()
        awaitContract("playback to move the playhead") {
          (recorder.playheads.lastOrNull() ?: Duration.ZERO) >
            Duration.ZERO
        }

        val before = recorder.playheads.size
        subject.engine.awaitComposition(layered).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to build a new player") { subject.engine.nativePlayer !== standing }
        val rebuilt = subject.engine.nativePlayer.shouldBeInstanceOf<CompositionPlayer>()

        awaitContract("the playhead to advance on the new player") {
          val since = recorder.playheads.drop(before)
          since.size >= 2 && since.last() > since.first()
        }

        recorder.lastState.playWhenReady shouldBe true
        rebuilt.onItsThread { playWhenReady } shouldBe true
        rebuilt.onItsThread { volume } shouldBe VOLUME
        subject.engine.pause()
      }
    }

  // The emulator has no ten-bit decoder, so a tone mapped frame cannot be read here. The planner stubs the grade on an
  // SDR
  // fixture, which is enough to pin the player that graph needs and the transport it takes.
  @Test
  fun aSpecThatFlipsTheGradeBuildsANewPlayerAndCarriesTheTransport() =
    contractTest { scope ->
      val subject = AndroidEngineUnderTest(scope, StubbedGradePlanner())
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(androidFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val standing = subject.engine.nativePlayer

        subject.engine.setVolume(VOLUME)
        subject.engine.play()
        awaitContract("playback to move the playhead") {
          (recorder.playheads.lastOrNull() ?: Duration.ZERO) > Duration.ZERO
        }
        val reached = recorder.playheads.last()

        val before = recorder.playheads.size
        subject.engine.setExportSpec(ExportSpec(hdr = HdrMode.ToneMapToSdr))
        awaitContract("the engine to build a new player") { subject.engine.nativePlayer !== standing }
        val rebuilt = subject.engine.nativePlayer.shouldBeInstanceOf<CompositionPlayer>()
        awaitContract("the playhead to advance on the new player") {
          val since = recorder.playheads.drop(before)
          since.size >= 2 && since.last() > since.first()
        }

        recorder.playheads.drop(before).first() shouldBeGreaterThanOrEqualTo reached
        recorder.lastState.playWhenReady shouldBe true
        rebuilt.onItsThread { playWhenReady } shouldBe true
        rebuilt.onItsThread { volume } shouldBe VOLUME

        subject.engine.pause()
      }
    }

  // A flip carries an SD clip's colour. The grade is stubbed, since the emulator has no ten-bit decoder, and a patch
  // the SD head moves by more than the tolerance is what would show a new player that lost it.
  @Test
  fun anSdClipReadsTheSameOnTheNewPlayerUnderEveryHdrMode() =
    contractTest { scope ->
      val subject = AndroidEngineUnderTest(scope, StubbedGradePlanner())
      val clip = Clip(androidSdTaggedClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH))
      val edit = EditComposition(listOf(Track(listOf(clip))))
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val auto =
          subject.engine.readback
            .awaitFrame(SD_READ)
            .asTestFrame()
            .patches()
        auto.maxOf { it.movedBySdHead() } shouldBeGreaterThan SD_TOLERANCE

        listOf(HdrMode.ToneMapToSdr, HdrMode.KeepHdr).forEach { mode ->
          val standing = subject.engine.nativePlayer
          subject.engine.setExportSpec(ExportSpec(hdr = mode))
          awaitContract("$mode to build a new player") { subject.engine.nativePlayer !== standing }
          awaitContract("the new player to be presentable") { recorder.lastState.hasComposition }

          val read =
            subject.engine.readback
              .awaitFrame(SD_READ)
              .asTestFrame()
              .patches()
          read.indices.forEach { patch ->
            withClue("$mode at patch $patch read ${read[patch].toList()} against ${auto[patch].toList()}") {
              read[patch].indices.all { abs(read[patch][it] - auto[patch][it]) <= SD_TOLERANCE } shouldBe true
            }
          }
        }
      }
    }

  private fun TestFrame.patches(): List<IntArray> =
    (1 until PATCHES).flatMap { row ->
      (1 until PATCHES).map { column ->
        val at = (size.height * row / PATCHES * size.width + size.width * column / PATCHES) * RGBA
        IntArray(RGB) { pixels[at + it].toInt() and BYTE_MASK }
      }
    }

  private fun IntArray.movedBySdHead(): Int {
    val shown = sdSourceDisplayed(DoubleArray(RGB) { this[it] / MAX_CODE }, SMPTE_C_PRIMARIES)
    return indices.maxOf { abs(shown[it] * MAX_CODE - this[it]).toInt() }
  }

  private suspend fun <T> CompositionPlayer.onItsThread(read: CompositionPlayer.() -> T): T =
    suspendCancellableCoroutine { waiting ->
      Handler(applicationLooper).post { waiting.resume(read()) }
    }

  /**
   * Plans every edit as usual, and gives the plan of [layered] its primary track a second time as another track.
   */
  private class SecondVideoSequencePlanner(
    private val layered: EditComposition,
  ) : Media3PreviewPlanner(CONTRACT_COMPONENTS) {
    override suspend fun plan(
      composition: EditComposition,
      policy: PreviewQualityPolicy,
      exportSpec: ExportSpec,
    ): PlanResult<Media3PreviewPlan, PlaybackError> {
      val result = super.plan(composition, policy, exportSpec)
      if (composition != layered || result !is PlanResult.Ready) return result
      val plan = result.plan
      return PlanResult.Ready(Media3PreviewPlan(plan.resolved.withPrimaryTwice(), plan.info, plan.bounds))
    }

    private fun ResolvedComposition.withPrimaryTwice(): ResolvedComposition =
      ResolvedComposition(
        tracks = tracks + tracks.first(),
        compositionEffects = compositionEffects,
        output = output,
        layoutSize = layoutSize,
        fill = fill,
        duration = duration,
        hdr = hdr,
        hdrTransfer = hdrTransfer,
        path = path,
        audio = audio,
        adjustments = adjustments,
        encoderName = encoderName,
        naturalSize = naturalSize,
      )
  }

  /**
   * Plans every edit as usual, and reports the tone mapped grade whenever the export spec asks for it.
   */
  private class StubbedGradePlanner : Media3PreviewPlanner(CONTRACT_COMPONENTS) {
    override suspend fun plan(
      composition: EditComposition,
      policy: PreviewQualityPolicy,
      exportSpec: ExportSpec,
    ): PlanResult<Media3PreviewPlan, PlaybackError> {
      val result = super.plan(composition, policy, exportSpec)
      if (exportSpec.hdr != HdrMode.ToneMapToSdr || result !is PlanResult.Ready) return result
      val plan = result.plan
      return PlanResult.Ready(Media3PreviewPlan(plan.resolved.toneMapped(), plan.info, plan.bounds))
    }

    private fun ResolvedComposition.toneMapped(): ResolvedComposition =
      ResolvedComposition(
        tracks = tracks,
        compositionEffects = compositionEffects,
        output = output,
        layoutSize = layoutSize,
        fill = fill,
        duration = duration,
        hdr = ResolvedHdr.ToneMap,
        hdrTransfer = null,
        path = path,
        audio = audio,
        adjustments = adjustments,
        encoderName = encoderName,
        naturalSize = naturalSize,
      )
  }

  private companion object {
    const val VOLUME = 0.2f
    const val SD_TOLERANCE = 3
    const val PATCHES = 6
    const val RGBA = 4
    const val RGB = 3
    const val BYTE_MASK = 0xFF
    const val MAX_CODE = 255.0
    val SD_READ: Duration = 500.milliseconds
  }
}
