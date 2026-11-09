package dev.jordond.filmstrip.sample.tryit

import androidx.compose.ui.graphics.toArgb
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.SamplePreset
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which steps of the edit the page has switched on.
 */
internal data class Steps(
  val trim: Boolean = false,
  val grade: Boolean = false,
  val title: Boolean = false,
)

/**
 * The clip the sample edits.
 */
internal val beachClip: SamplePreset by lazy { samplePresets.first { it.file == "beach-sunset.mp4" } }

/**
 * The part of the clip TRIM keeps, in source time.
 */
internal val TRIM_RANGE: ClosedRange<Duration> = 2.seconds..8.seconds

/**
 * The saturation GRADE sets.
 */
internal const val GRADE_SATURATION: Float = 2f

/**
 * The contrast GRADE sets.
 */
internal const val GRADE_CONTRAST: Float = 1.15f

/**
 * The text TITLE draws.
 */
internal const val TITLE_TEXT: String = "SUMMER '96"

/**
 * How TITLE's text is drawn, the display face in the landing page's cream.
 */
internal val titleStyle: TextStyle =
  TextStyle(fontSize = 0.05f, resolvedFontFamily = "Rubik Mono One", color = Tokens.titleCream.toArgb())

/**
 * Where TITLE's text sits, centred and part way down the frame like the hero's.
 */
internal val titleAnchor: Anchor = Anchor(0.5f, 0.18f)

/**
 * The edit [steps] describe over [source], which the preview plays and EXPORT writes.
 */
internal fun beachEdit(
  source: MediaSource,
  steps: Steps,
): Composition =
  compositionOf {
    clip(source) { if (steps.trim) trim(TRIM_RANGE) }
    effects {
      if (steps.grade) {
        saturation(GRADE_SATURATION)
        contrast(GRADE_CONTRAST)
      }
      if (steps.title) textOverlay(TITLE_TEXT, style = titleStyle, anchor = titleAnchor)
    }
  }

/**
 * Where in the source a [position] on the edit [steps] describe falls.
 */
internal fun sourcePosition(
  position: Duration,
  steps: Steps,
): Duration = if (steps.trim) TRIM_RANGE.start + position else position

/**
 * Where on the edit [steps] describe the time [source] falls, or the nearest end of the trim when the trim cuts it.
 */
internal fun editPosition(
  source: Duration,
  steps: Steps,
): Duration =
  if (steps.trim) {
    source.coerceIn(TRIM_RANGE.start, TRIM_RANGE.endInclusive) - TRIM_RANGE.start
  } else {
    source
  }

/**
 * Plans [edit] against [spec] and writes it to [sink], reporting a refusal the way a failed run reports.
 */
internal fun writeExport(
  filmstrip: Filmstrip,
  edit: Composition,
  spec: ExportSpec,
  sink: MediaSink,
): Flow<ExportStatus> =
  flow {
    when (val verdict = filmstrip.plan(edit, spec)) {
      is Verdict.Capable -> emitAll(filmstrip.export(verdict.plan, sink))
      is Verdict.Degraded -> emitAll(filmstrip.export(verdict.plan, sink))
      is Verdict.Incapable -> emit(ExportStatus.Failure(verdict.reasons))
    }
  }
