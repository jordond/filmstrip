package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.diagnostics.CodecRole
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.diagnostics.PlatformFailure
import dev.jordond.filmstrip.diagnostics.attachFailure
import dev.jordond.filmstrip.diagnostics.redactLocation
import dev.jordond.filmstrip.diagnostics.redactLocations
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportEngineFactory
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalFilmstripApi::class)
class FailureReportTest {
  private val events = mutableListOf<DiagnosticEvent>()

  @Test
  fun aFailedExportIsReportedOnceWithItsStageArmAndRedactedMessage() =
    runTest {
      val error = FilmstripError.SourceUnreadable(PATH, "Could not open $PATH for reading.")
      val filmstrip = filmstripOf { components -> RunningEngine(components, ExportStatus.Failure(error)) }

      filmstrip.export(COMPOSITION, SPEC, SINK).toList()

      val event = events.single()
      event.name shouldBe DiagnosticEvent.FAILURE
      event.source shouldBe "filmstrip"
      event.detail[DiagnosticEvent.Detail.STAGE] shouldBe "export"
      event.detail[DiagnosticEvent.Detail.ERROR] shouldBe "SourceUnreadable"
      event.detail[DiagnosticEvent.Detail.MESSAGE] shouldBe "Could not open ${redactLocation(PATH)} for reading."
      event.detail[DiagnosticEvent.Detail.SOURCES] shouldBe "${redactLocation(PATH)},${redactLocation(URI)}"
      event.detail shouldNotContainKey DiagnosticEvent.Detail.ERROR_CODE
    }

  @Test
  fun whatTheBackendAttachedLandsUnderItsKeys() =
    runTest {
      val error = FilmstripError.DecoderRejectedInput("video/avc", "Decoding failed.")
      val platform =
        PlatformFailure(
          backend = "media3",
          code = 4003,
          codeName = "ERROR_CODE_DECODING_FAILED",
          domain = "AVFoundationErrorDomain",
          codecName = "c2.goldfish.h264.decoder",
          codecRole = CodecRole.Decoder,
          codecFormat = "mimeType=video/avc, res=1920x1080, uri=$URI",
          inputMime = "video/avc",
          inputCodecs = "avc1.64001F",
          inputSize = "1920x1080",
          inputFrameRate = 29.97f,
          causes = listOf("MediaCodecDecoderException: Decoder failed", "IllegalStateException: at $PATH"),
        )
      val filmstrip =
        filmstripOf { components ->
          components.attachFailure(error, platform)
          RunningEngine(components, ExportStatus.Failure(error))
        }

      filmstrip.export(COMPOSITION, SPEC, SINK).toList()

      val event = events.single()
      event.source shouldBe "media3"
      event.detail shouldBe
        mapOf(
          DiagnosticEvent.Detail.STAGE to "export",
          DiagnosticEvent.Detail.ERROR to "DecoderRejectedInput",
          DiagnosticEvent.Detail.MESSAGE to "Decoding failed.",
          DiagnosticEvent.Detail.SOURCES to "${redactLocation(PATH)},${redactLocation(URI)}",
          DiagnosticEvent.Detail.ERROR_CODE to "4003",
          DiagnosticEvent.Detail.ERROR_NAME to "ERROR_CODE_DECODING_FAILED",
          DiagnosticEvent.Detail.ERROR_DOMAIN to "AVFoundationErrorDomain",
          DiagnosticEvent.Detail.CODEC_NAME to "c2.goldfish.h264.decoder",
          DiagnosticEvent.Detail.CODEC_ROLE to "decoder",
          DiagnosticEvent.Detail.CODEC_FORMAT to redactLocations("mimeType=video/avc, res=1920x1080, uri=$URI"),
          DiagnosticEvent.Detail.INPUT_MIME to "video/avc",
          DiagnosticEvent.Detail.INPUT_CODECS to "avc1.64001F",
          DiagnosticEvent.Detail.INPUT_SIZE to "1920x1080",
          DiagnosticEvent.Detail.INPUT_FRAME_RATE to 29.97f.toString(),
          DiagnosticEvent.Detail.CAUSES to
            "MediaCodecDecoderException: Decoder failed\nIllegalStateException: at ${redactLocation(PATH)}",
        )
    }

  @Test
  fun anAttachmentOnAnEqualButDifferentErrorIsNotRead() =
    runTest {
      val reported = FilmstripError.Underlying(7, "Stopped.")
      val filmstrip =
        filmstripOf { components ->
          components.attachFailure(FilmstripError.Underlying(7, "Stopped."), PlatformFailure("media3", code = 7))
          RunningEngine(components, ExportStatus.Failure(reported))
        }

      filmstrip.export(COMPOSITION, SPEC, SINK).toList()

      events.single().source shouldBe "filmstrip"
    }

  @Test
  fun aSucceededExportReportsNothing() =
    runTest {
      val success =
        ExportStatus.Success(
          SINK,
          MediaInfo(4.seconds, null, emptyList(), isExportable = true),
          emptyList(),
        )
      val filmstrip = filmstripOf { components -> RunningEngine(components, success) }

      filmstrip.export(COMPOSITION, SPEC, SINK).toList()
      filmstrip.export(planOf(COMPOSITION), SINK).toList()

      events.shouldBeEmpty()
    }

  @Test
  fun anIncapablePlanIsOneFailureNamingEveryReason() =
    runTest {
      val reasons =
        listOf(
          FilmstripError.InvalidComposition("No tracks."),
          FilmstripError.NoEncoder(VideoCodec.Hevc, "No HEVC encoder."),
        )
      val filmstrip =
        filmstripOf { components -> RunningEngine(components, null, Verdict.Incapable(reasons, null)) }

      filmstrip.export(COMPOSITION, SPEC, SINK).toList()

      val event = events.single()
      event.detail[DiagnosticEvent.Detail.ERROR] shouldBe "InvalidComposition,NoEncoder"
      event.detail[DiagnosticEvent.Detail.MESSAGE] shouldBe "No tracks.\nNo HEVC encoder."
    }

  @Test
  fun aMissingExportBackendIsReportedOnBothOverloads() =
    runTest {
      val filmstrip = filmstripOf(engine = null)

      filmstrip.export(COMPOSITION, SPEC, SINK).toList()
      filmstrip.export(planOf(COMPOSITION), SINK).toList()

      events shouldHaveSize 2
      events.map { it.detail[DiagnosticEvent.Detail.ERROR] } shouldBe listOf("BackendMissing", "BackendMissing")
    }

  @Test
  fun aPlanHandedInReportsItsFailureOnce() =
    runTest {
      val error = FilmstripError.SinkUnwritable(SINK_PATH, "Read-only.")
      val filmstrip = filmstripOf { components -> RunningEngine(components, ExportStatus.Failure(error)) }

      filmstrip.export(planOf(COMPOSITION), SINK).toList()

      events.single().detail[DiagnosticEvent.Detail.STAGE] shouldBe "export"
    }

  @Test
  fun aListenerThatThrowsLeavesTheFailureToTheCaller() =
    runTest {
      val error = FilmstripError.Underlying(1, "Stopped.")
      val builder =
        ComponentRegistry
          .Builder()
          .addDiagnosticListener { error("listener broke") }
          .addExportEngineFactory { RunningEngine(it, ExportStatus.Failure(error)) }

      val statuses = DefaultFilmstrip(builder.build()).export(COMPOSITION, SPEC, SINK).toList()

      statuses.last().shouldBeInstanceOf<ExportStatus.Failure>().error shouldBe error
    }

  private fun filmstripOf(engine: ((EngineComponents) -> ExportEngine)?): DefaultFilmstrip {
    val builder = ComponentRegistry.Builder().addDiagnosticListener(DiagnosticListener { events += it })
    if (engine != null) builder.addExportEngineFactory(ExportEngineFactory { engine(it) })
    return DefaultFilmstrip(builder.build())
  }

  private class RunningEngine(
    private val components: EngineComponents,
    private val status: ExportStatus?,
    private val verdict: Verdict? = null,
  ) : ExportEngine {
    override suspend fun capabilities(): CapabilitiesResult = error("Not asked")

    override suspend fun plan(
      composition: Composition,
      spec: ExportSpec,
    ): Verdict = verdict ?: Verdict.Capable(planOf(composition))

    override fun export(
      plan: ExportPlan,
      to: MediaSink,
    ): Flow<ExportStatus> =
      flow {
        emit(ExportStatus.Started)
        status?.let { emit(it) }
      }

    override fun parityOf(specId: EffectId): EffectParity? = null
  }

  private companion object {
    const val PATH = "/storage/emulated/0/DCIM/Camera/holiday.mp4"
    const val URI = "content://media/external/video/media/42"
    const val SINK_PATH = "/data/user/0/app/cache/out.mp4"

    val COMPOSITION: Composition =
      compositionOf {
        clip(MediaSource.of(PATH))
        clip(MediaSource.ofUri(URI))
      }

    val SPEC = ExportSpec(heightPx = 720)

    val SINK: MediaSink = MediaSink.of(SINK_PATH)

    fun planOf(composition: Composition): ExportPlan =
      ExportPlan(
        path = ExportPath.Transcode,
        output = OutputFormat(Size(1280, 720), VideoCodec.H264, AudioCodec.Aac, null, 30, null),
        effectOrder = emptyList(),
        estimate = ExportEstimate(null, null, null, isCopy = false),
        parity = EffectParity.Exact,
        duration = 4.seconds,
        copyBlockedBy = emptyList(),
        composition = composition,
        spec = SPEC,
      )
  }
}
