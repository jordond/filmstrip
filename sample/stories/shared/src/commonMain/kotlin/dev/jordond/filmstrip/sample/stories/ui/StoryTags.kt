package dev.jordond.filmstrip.sample.stories.ui

/**
 * The test tags of the editor's parts.
 */
internal object StoryTags {
  const val CHROME = "story-chrome"
  const val BIN = "story-bin"
  const val GUIDE_VERTICAL = "story-guide-vertical"
  const val GUIDE_HORIZONTAL = "story-guide-horizontal"
  const val GUIDE_LEVEL = "story-guide-level"
  const val TRAY = "story-tray"
  const val TRAY_FILTER = "story-tray-filter"
  const val DRAW_DONE = "story-draw-done"
  const val DRAW_UNDO = "story-draw-undo"
  const val DRAW_WIDTH = "story-draw-width"
  const val TIMING_STRIP = "story-timing-strip"
  const val TIMING_WHOLE = "story-timing-whole"
  const val LOOK_NAME = "story-look-name"
  const val EXPORT_SHEET = "story-export-sheet"
  const val POINTER_STAGE = "story-pointer-stage"
  const val STAGE = "story-stage"
  const val INSPECTOR_DELETE = "story-inspector-delete"

  /**
   * The tag of the legend's line for the binding called [name].
   */
  fun legend(name: String): String = "story-legend-$name"

  const val TEXT_FIELD = "story-text-field"
  const val TEXT_DONE = "story-text-done"
  const val TEXT_ALIGN = "story-text-align"
  const val TEXT_PLATE = "story-text-plate"
  const val TEXT_ENTRANCE = "story-text-entrance"
  const val TEXT_SIZE = "story-text-size"
}
