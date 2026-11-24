package dev.jordond.filmstrip.diagnostics

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.player.PlaybackError

/**
 * Emits an event to every registered listener.
 *
 * A listener that throws is ignored: diagnostics are the least important thing in an export, and
 * failing one is not worth failing the render for.
 *
 * @param source Which component is emitting.
 * @param name The event's stable key.
 * @param detail The payload.
 */
@InternalFilmstripApi
public fun EngineComponents.report(
  source: String,
  name: String,
  detail: Map<String, String>,
) {
  if (diagnosticListeners.isEmpty()) return

  val event = DiagnosticEvent(source, name, detail)
  diagnosticListeners.forEach { listener ->
    runCatching { listener.onEvent(event) }
  }
}

/**
 * Where a failure happened, written under [DiagnosticEvent.Detail.STAGE].
 */
internal enum class FailureStage(
  val key: String,
) {
  Playback("playback"),
  Export("export"),
}

/**
 * Emits one [DiagnosticEvent.FAILURE] for an export that finished with [reasons].
 *
 * The first reason a backend attached a [PlatformFailure] to supplies the platform's detail.
 */
internal fun EngineComponents.reportExportFailure(
  reasons: List<FilmstripError>,
  composition: Composition?,
) {
  val platform = reasons.firstNotNullOfOrNull { failureNotes.take(it) }
  if (diagnosticListeners.isEmpty()) return
  reportFailure(
    stage = FailureStage.Export,
    arms = reasons.map { it.armName() },
    messages = reasons.map { it.message },
    composition = composition,
    platform = platform,
  )
}

/**
 * Emits one [DiagnosticEvent.FAILURE] for a player that reached [error].
 */
internal fun EngineComponents.reportPlaybackFailure(
  error: PlaybackError,
  composition: Composition?,
) {
  val platform = failureNotes.take(error)
  if (diagnosticListeners.isEmpty()) return
  reportFailure(
    stage = FailureStage.Playback,
    arms = listOf(error.armName()),
    messages = listOf(error.message),
    composition = composition,
    platform = platform,
  )
}

private fun EngineComponents.reportFailure(
  stage: FailureStage,
  arms: List<String>,
  messages: List<String>,
  composition: Composition?,
  platform: PlatformFailure?,
) {
  val detail =
    buildMap {
      put(DiagnosticEvent.Detail.STAGE, stage.key)
      put(DiagnosticEvent.Detail.ERROR, arms.joinToString(","))
      put(DiagnosticEvent.Detail.MESSAGE, messages.joinToString("\n") { redactLocations(it) })
      put(DiagnosticEvent.Detail.SOURCES, composition?.redactedSources().orEmpty().joinToString(","))
      if (platform != null) putPlatform(platform)
    }
  report(platform?.backend ?: NO_BACKEND, DiagnosticEvent.FAILURE, detail)
}

private fun MutableMap<String, String>.putPlatform(platform: PlatformFailure) {
  platform.code?.let { put(DiagnosticEvent.Detail.ERROR_CODE, it.toString()) }
  platform.codeName?.let { put(DiagnosticEvent.Detail.ERROR_NAME, it) }
  platform.domain?.let { put(DiagnosticEvent.Detail.ERROR_DOMAIN, it) }
  platform.codecName?.let { put(DiagnosticEvent.Detail.CODEC_NAME, it) }
  platform.codecRole?.let { put(DiagnosticEvent.Detail.CODEC_ROLE, it.key) }
  platform.codecFormat?.let { put(DiagnosticEvent.Detail.CODEC_FORMAT, redactLocations(it)) }
  platform.inputMime?.let { put(DiagnosticEvent.Detail.INPUT_MIME, it) }
  platform.inputCodecs?.let { put(DiagnosticEvent.Detail.INPUT_CODECS, it) }
  platform.inputSize?.let { put(DiagnosticEvent.Detail.INPUT_SIZE, it) }
  platform.inputFrameRate?.let { put(DiagnosticEvent.Detail.INPUT_FRAME_RATE, it.toString()) }
  if (platform.causes.isNotEmpty()) {
    put(DiagnosticEvent.Detail.CAUSES, platform.causes.joinToString("\n") { redactLocations(it) })
  }
}

private fun FilmstripError.armName(): String =
  when (this) {
    is FilmstripError.NoEncoder -> "NoEncoder"
    is FilmstripError.UnsupportedResolution -> "UnsupportedResolution"
    is FilmstripError.DecoderRejectedInput -> "DecoderRejectedInput"
    is FilmstripError.SourceNotExportable -> "SourceNotExportable"
    is FilmstripError.InvalidComposition -> "InvalidComposition"
    is FilmstripError.UnsupportedEffect -> "UnsupportedEffect"
    is FilmstripError.UnpreviewableEffect -> "UnpreviewableEffect"
    is FilmstripError.PlatformEffectAsymmetry -> "PlatformEffectAsymmetry"
    is FilmstripError.InterruptedByBackgrounding -> "InterruptedByBackgrounding"
    is FilmstripError.InsufficientStorage -> "InsufficientStorage"
    is FilmstripError.SourceUnreadable -> "SourceUnreadable"
    is FilmstripError.SinkUnwritable -> "SinkUnwritable"
    is FilmstripError.UnsupportedStillFormat -> "UnsupportedStillFormat"
    is FilmstripError.BackendMissing -> "BackendMissing"
    is FilmstripError.ToolchainMissing -> "ToolchainMissing"
    is FilmstripError.TimedOut -> "TimedOut"
    is FilmstripError.Underlying -> "Underlying"
  }

private fun PlaybackError.armName(): String =
  when (this) {
    is PlaybackError.SourceUnreadable -> "SourceUnreadable"
    is PlaybackError.UnsupportedFormat -> "UnsupportedFormat"
    is PlaybackError.DecoderUnavailable -> "DecoderUnavailable"
    is PlaybackError.SourceNotExportable -> "SourceNotExportable"
    is PlaybackError.BackendMissing -> "BackendMissing"
    is PlaybackError.Underlying -> "Underlying"
  }

/**
 * The event source of a failure no backend attached anything to.
 */
private const val NO_BACKEND = "filmstrip"
