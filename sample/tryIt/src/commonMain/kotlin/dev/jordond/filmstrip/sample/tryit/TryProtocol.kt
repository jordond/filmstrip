package dev.jordond.filmstrip.sample.tryit

import kotlin.time.Duration

/**
 * One change the page asks of the app.
 */
internal sealed interface Control {
  data class SetSteps(
    val steps: Steps,
  ) : Control

  data object Export : Control

  data object Save : Control

  data object Cancel : Control

  data object Retry : Control
}

/**
 * What the app tells the page, whenever any of it changes.
 *
 * @property position Where the playhead is, in source time.
 * @property duration How long the clip is, or null until a player has it up.
 * @property trimRange The part of the clip the edit keeps, in source time, or null with no trim.
 * @property export Where an export stands.
 * @property exportRefusal Why this device can't export at all, or null when it can or hasn't answered yet.
 * @property error The text of the last refusal or failed load, or null.
 * @property awaitingTap Whether the browser stopped playback and only a tap on the picture can start it again.
 */
internal data class TryStatus(
  val position: Duration = Duration.ZERO,
  val duration: Duration? = null,
  val trimRange: ClosedRange<Duration>? = null,
  val export: ExportState = ExportState.Idle,
  val exportRefusal: String? = null,
  val error: String? = null,
  val awaitingTap: Boolean = false,
)

/**
 * A parsed message, read one named field at a time. A field that is missing or the wrong type reads as null.
 */
internal interface WireFields {
  fun string(name: String): String?

  fun boolean(name: String): Boolean?
}

/**
 * The longest message the app parses, comfortably over what any [Control] encodes to.
 */
internal const val MAX_WIRE_CHARS: Int = 4096

/**
 * Reads one message from the page, or null for anything it does not know.
 */
internal fun controlOf(fields: WireFields): Control? {
  if (fields.string("type") != "control") return null
  return when (fields.string("name")) {
    "steps" -> fields.steps()?.let(Control::SetSteps)
    "export" -> Control.Export
    "save" -> Control.Save
    "cancel" -> Control.Cancel
    "retry" -> Control.Retry
    else -> null
  }
}

private fun WireFields.steps(): Steps? {
  val trim = boolean("trim") ?: return null
  val grade = boolean("grade") ?: return null
  val title = boolean("title") ?: return null
  return Steps(trim, grade, title)
}

/**
 * The message that says the app is up.
 */
internal fun encodeReady(): String = """{"type":"ready"}"""

/**
 * The message that carries [status].
 */
internal fun encodeStatus(status: TryStatus): String =
  buildString {
    append("""{"type":"status","positionMs":""").append(status.position.inWholeMilliseconds)
    append(""","durationMs":""").append(status.duration?.inWholeMilliseconds?.toString() ?: "null")
    append(""","trimRange":""").append(status.trimRange?.let(::encodeRange) ?: "null")
    append(""","export":""").append(encodeExport(status.export))
    append(""","exportRefusal":""").append(status.exportRefusal?.let(::jsonString) ?: "null")
    append(""","error":""").append(status.error?.let(::jsonString) ?: "null")
    append(""","awaitingTap":""").append(status.awaitingTap)
    append('}')
  }

private fun encodeRange(range: ClosedRange<Duration>): String =
  """{"startMs":${range.start.inWholeMilliseconds},"endMs":${range.endInclusive.inWholeMilliseconds}}"""

private fun encodeExport(export: ExportState): String =
  when (export) {
    ExportState.Idle -> """{"state":"idle"}"""
    is ExportState.Running -> """{"state":"running","fraction":${export.fraction}}"""
    is ExportState.Done -> """{"state":"done"}"""
  }

private fun jsonString(text: String): String =
  buildString {
    append('"')
    text.forEach { char ->
      when {
        char == '"' -> append("\\\"")
        char == '\\' -> append("\\\\")
        char == '\n' -> append("\\n")
        char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
        else -> append(char)
      }
    }
    append('"')
  }
