package dev.jordond.filmstrip.ffmpeg.internal.lowering

// The escaping is a property of the tool, not of the effect, so it lives with the ffmpeg lowering.
// The shell is not one of the levels: the graph reaches the child as one element of an
// argument list, so nothing here escapes for it.

// ffmpeg unescapes an option value twice. The graph parser takes one level off the whole
// description, where [],;' and the backslash are its own, and av_opt then takes another off the
// value it hands the filter, where the colon separates one option from the next. A value is escaped
// against both, innermost first, or a path holding a colon reaches the filter split in two.
private const val OPTION_ESCAPED = "\\':"
private const val GRAPH_ESCAPED = "\\'[],;"

internal fun escapeFilterValue(value: String): String = value.escaping(OPTION_ESCAPED).escaping(GRAPH_ESCAPED)

private fun String.escaping(special: String): String =
  buildString(length) {
    this@escaping.forEach { character ->
      if (character in special) append('\\')
      append(character)
    }
  }
