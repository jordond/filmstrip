package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.crop
import dev.jordond.filmstrip.effects.flip
import dev.jordond.filmstrip.effects.rotate
import dev.jordond.filmstrip.effects.text
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.TrimStrategy
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Drives one pick-to-export session against a [Filmstrip], exposing each step as compose state.
 *
 * Everything it runs is cancellable by cancelling [scope], and every arm of the API it hits is a
 * value to show rather than an exception to catch, which is the point of the sample.
 */
@Stable
class SampleAppState(
  private val filmstrip: Filmstrip,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
  var source: MediaSource? by mutableStateOf(null)
    private set

  var probing by mutableStateOf(false)
    private set
  var pickFailure: String? by mutableStateOf(null)
    private set
  var probe: ProbeResult? by mutableStateOf(null)
    private set

  var trimming by mutableStateOf(false)
  var trimStartSeconds by mutableStateOf(0f)
  var trimEndSeconds by mutableStateOf(0f)
  var rotationDegrees by mutableStateOf(0)
  var flipHorizontal by mutableStateOf(false)
  var cropAspect: AspectRatio? by mutableStateOf(null)
  var caption by mutableStateOf("")
  var muteAudio by mutableStateOf(false)

  var targetHeight: Int? by mutableStateOf(1080)
  var videoCodec by mutableStateOf(VideoCodec.Auto)
  var audioCodec by mutableStateOf(AudioCodec.Auto)
  var bitrateMbps: Int? by mutableStateOf(4)
  var frameRate: Int? by mutableStateOf(null)
  var hdr by mutableStateOf(HdrMode.Auto)
  var trimStrategy by mutableStateOf(TrimStrategy.Precise)
  var strict by mutableStateOf(false)

  var planning by mutableStateOf(false)
    private set
  var verdict: Verdict? by mutableStateOf(null)
    private set

  var exporting by mutableStateOf(false)
    private set
  var exportProgress: ExportStatus.Progress? by mutableStateOf(null)
    private set
  var exportAdjustments by mutableStateOf(emptyList<Adjustment>())
    private set
  var exported: ExportStatus.Success? by mutableStateOf(null)
    private set
  var exportedInfo: ProbeResult? by mutableStateOf(null)
    private set
  var exportFailure: ExportError? by mutableStateOf(null)
    private set

  var capabilities: CapabilitiesResult? by mutableStateOf(null)
    private set

  private var exportJob: Job? = null

  val sourceDuration: Duration?
    get() = (probe as? ProbeResult.Success)?.info?.duration

  val sourceDurationSeconds: Float?
    get() = sourceDuration?.toSeconds()

  fun onPicked(source: MediaSource?) {
    this.source = source
    pickFailure = null
    resetRun()

    if (source != null) {
      probing = true
      scope.launch {
        try {
          probe = filmstrip.probe(source)
          val duration = sourceDuration
          if (duration != null) {
            trimStartSeconds = 0f
            trimEndSeconds = duration.toSeconds()
          }
        } finally {
          probing = false
        }
      }
    }
  }

  /**
   * Records that the platform picker could not finish, which is not the same as the user declining.
   */
  fun onPickFailed(message: String?) {
    pickFailure = message ?: "No reason given."
  }

  /**
   * Drops anything an edit or source change invalidated: the plan, and the export that came from it.
   */
  fun onEditChanged() {
    verdict = null
    exported = null
    exportedInfo = null
    exportFailure = null
    exportProgress = null
    exportAdjustments = emptyList()
  }

  fun composition(): EditComposition = filmstrip.composition {
    clip(requireNotNull(source)) {
      val duration = sourceDuration
      if (trimming && duration != null) {
        val start = trimStartSeconds.toDuration().coerceIn(Duration.ZERO, duration)
        val end = trimEndSeconds.toDuration().coerceIn(start, duration)
        if (end > start) trim(start, end)
      }
      effects {
        if (rotationDegrees != 0) rotate(rotationDegrees)
        if (flipHorizontal) flip(FlipAxis.Horizontal)
        cropAspect?.let { crop(it) }
        if (caption.isNotBlank()) text(caption)
      }
      if (muteAudio) audio(AudioLevel.Mute)
    }
  }

  fun spec(): ExportSpec = ExportSpec(
    targetHeight = targetHeight,
    bitrate = bitrateMbps?.let(Bitrate::mbps),
    videoCodec = videoCodec,
    audioCodec = audioCodec,
    frameRate = frameRate,
    hdr = hdr,
    trim = trimStrategy,
    strict = strict,
  )

  fun plan() {
    verdict = null
    planning = true
    scope.launch {
      try {
        verdict = filmstrip.plan(composition(), spec())
      } finally {
        planning = false
      }
    }
  }

  fun export(plan: ExportPlan) {
    exportJob?.cancel()
    exported = null
    exportedInfo = null
    exportFailure = null
    exportProgress = null
    exportAdjustments = emptyList()

    exportJob = scope.launch {
      exporting = true
      try {
        filmstrip.export(plan, MediaSink.temporary()).collect { status ->
          when (status) {
            is ExportStatus.Started -> Unit
            is ExportStatus.Adjusted -> exportAdjustments = status.adjustments
            is ExportStatus.Progress -> exportProgress = status
            is ExportStatus.Success -> {
              exported = status
              exportedInfo = filmstrip.probe(status.output.asSource())
            }
            is ExportStatus.Failure -> exportFailure = status.error
          }
        }
      } finally {
        exporting = false
      }
    }
  }

  fun cancelExport() {
    exportJob?.cancel()
  }

  fun refreshCapabilities() {
    scope.launch {
      capabilities = filmstrip.capabilities()
    }
  }

  private fun resetRun() {
    probe = null
    verdict = null
    exported = null
    exportedInfo = null
    exportFailure = null
    exportProgress = null
    exportAdjustments = emptyList()
    trimStartSeconds = 0f
    trimEndSeconds = 0f
  }

  private fun Float.toDuration(): Duration = toDouble().seconds

  private fun Duration.toSeconds(): Float = inWholeMilliseconds / 1000f

  private fun MediaSink.asSource(): MediaSource =
    when (this) {
      is MediaSink.Path -> MediaSource.of(path)
      is MediaSink.Uri -> MediaSource.ofUri(uri)
      MediaSink.Temporary -> throw IllegalStateException("A resolved temporary sink reports a path or uri.")
    }
}
