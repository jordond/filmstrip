package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The iOS simulator's own decoder answers, as measured on it.
 *
 * What a ProRes 4444 clip's alpha draws as is held on macOS and an iPhone by AppleSourceAlphaHardwareTest.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSimulatorDecoderHardwareTest {
  /**
   * On the simulator a ProRes 4444 source passes the probe and the plan, and its export then fails as it starts up
   * with `DecoderRejectedInput`, because the simulator has no ProRes decoder. The failure is the export's only status,
   * so it never reports `Started`.
   *
   * The probe and the plan are read first, so the failure is the decoder's and not a clip that never opened. A
   * refusal at plan time is owed here, and this test changes with it.
   */
  @Test
  fun `the simulator plans a prores 4444 clip and its export fails before it starts`() =
    runTest(timeout = TIMEOUT) {
      val source = MediaSource.Bytes(Base64.decode(AlphaClips.OPAQUE))
      val composition =
        compositionOf {
          clip(source)
          fill(Fill.Solid(SLATE))
        }
      val output = NSTemporaryDirectory() + "filmstrip-apple-simulator-prores.mp4"
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)
      val filmstrip = Filmstrip { avFoundationBackend() }

      val (probed, verdict, statuses) =
        withContext(Dispatchers.Default) {
          val probed = filmstrip.probe(source)
          val verdict = filmstrip.plan(composition, ExportSpec())
          val finished = (verdict as? Verdict.Capable)?.let { filmstrip.export(it.plan, MediaSink.of(output)).toList() }
          Triple(probed, verdict, finished.orEmpty())
        }
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)

      withClue("the simulator could not open the ProRes 4444 clip, so nothing was asked of its decoder") {
        val video =
          probed
            .shouldBeInstanceOf<ProbeResult.Success>()
            .info.video
            .shouldNotBeNull()
        withClue("the probe no longer reads a ProRes 4444 track's alpha off its format") {
          video.hasAlpha.shouldBeTrue()
        }
      }
      withClue("the plan no longer passes a ProRes 4444 source on the simulator") {
        verdict.shouldBeInstanceOf<Verdict.Capable>()
      }
      withClue("the export no longer fails as its only status, its statuses were $statuses") {
        statuses shouldHaveSize 1
      }
      withClue("the simulator drew a ProRes 4444 clip, so its decoder limit no longer holds") {
        statuses
          .last()
          .shouldBeInstanceOf<ExportStatus.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()
      }
    }

  private companion object {
    val TIMEOUT = 2.minutes
    const val SLATE = 0xFF2F4F6F.toInt()
  }
}
