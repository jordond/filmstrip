package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What a run writes to the session log that a bug report is built from.
 */
@OptIn(InternalFilmstripApi::class)
class ExportDiagnosticsTest {
  @Test
  fun `an adjusted export logs what was asked for and what it became`() {
    val adjustment = Adjustment(AdjustmentKind.CodecFallback, "hevc", "h264", "No HEVC encoder.")
    val state =
      SampleAppState(
        filmstrip = Filmstrip { autoRegister(false).addExportEngineFactory { AdjustingEngine(listOf(adjustment)) } },
        scope = CoroutineScope(Dispatchers.Unconfined),
      )

    state.export(plan())

    state.recorder.events
      .single { it.label == "export.adjusted" }
      .detail shouldBe
      mapOf("CodecFallback" to "hevc -> h264")
  }

  @Test
  fun `two adjustments of one kind both reach the log`() {
    val adjustments =
      listOf(
        Adjustment(AdjustmentKind.CodecFallback, "hevc", "h264", "No HEVC encoder."),
        Adjustment(AdjustmentKind.CodecFallback, "opus", "aac", "No Opus encoder."),
      )
    val state =
      SampleAppState(
        filmstrip = Filmstrip { autoRegister(false).addExportEngineFactory { AdjustingEngine(adjustments) } },
        scope = CoroutineScope(Dispatchers.Unconfined),
      )

    state.export(plan())

    state.recorder.events
      .single { it.label == "export.adjusted" }
      .detail shouldBe
      mapOf("CodecFallback" to "hevc -> h264 | opus -> aac")
  }

  private fun plan(): ExportPlan =
    ExportPlan(
      path = ExportPath.Transcode,
      output = OutputFormat(Size(1280, 720), VideoCodec.H264, AudioCodec.Aac, null, 30, null),
      effectOrder = emptyList(),
      estimate = ExportEstimate(null, null, null, isCopy = false),
      parity = EffectParity.Exact,
      duration = 4.seconds,
      copyBlockedBy = emptyList(),
      composition = Composition(tracks = emptyList()),
      spec = ExportSpec(),
    )

  private class AdjustingEngine(
    private val adjustments: List<Adjustment>,
  ) : ExportEngine {
    override suspend fun capabilities(): CapabilitiesResult = fail("The test asks for no capabilities.")

    override suspend fun plan(
      composition: Composition,
      spec: ExportSpec,
    ): Verdict = fail("The test hands over its own plan.")

    override fun export(
      plan: ExportPlan,
      to: MediaSink,
    ): Flow<ExportStatus> = flowOf(ExportStatus.Adjusted(adjustments))

    override fun parityOf(specId: String): EffectParity? = null
  }
}
