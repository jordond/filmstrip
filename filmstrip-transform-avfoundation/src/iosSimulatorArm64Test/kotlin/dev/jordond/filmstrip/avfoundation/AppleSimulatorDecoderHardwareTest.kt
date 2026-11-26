package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The iOS simulator's own decoder answers, as measured on it.
 *
 * What a ProRes 4444 clip's alpha draws as is held on macOS and an iPhone by AppleSourceAlphaHardwareTest.
 */
class AppleSimulatorDecoderHardwareTest {
  /**
   * On the simulator a ProRes 4444 source is refused at probe with `DecoderRejectedInput`, because the simulator has no
   * ProRes decoder, and a plan of it is refused with the same error before any export starts.
   */
  @Test
  fun `the simulator refuses a prores 4444 clip at probe and at plan`() =
    runTest(timeout = TIMEOUT) {
      val source = MediaSource.Bytes(Base64.decode(AlphaClips.OPAQUE))
      val composition =
        compositionOf {
          clip(source)
          fill(Fill.Solid(SLATE))
        }
      val filmstrip = Filmstrip { avFoundationBackend() }

      val (probed, verdict) =
        withContext(Dispatchers.Default) { filmstrip.probe(source) to filmstrip.plan(composition, ExportSpec()) }

      withClue("the simulator decoded a ProRes 4444 clip, so its decoder limit no longer holds") {
        probed
          .shouldBeInstanceOf<ProbeResult.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()
      }
      verdict
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()
        .shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()
    }

  private companion object {
    val TIMEOUT = 2.minutes
    const val SLATE = 0xFF2F4F6F.toInt()
  }
}
