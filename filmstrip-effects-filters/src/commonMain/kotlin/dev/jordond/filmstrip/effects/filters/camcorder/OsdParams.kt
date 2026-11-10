package dev.jordond.filmstrip.effects.filters.camcorder

// The tuned look of a camcorder display. Every backend reads these through CamcorderOsd, never on its own.
internal object OsdParams {
  // The height of a line of text, a fraction of the frame height.
  const val TEXT_SIZE = 0.045f

  // The title safe inset from the left and right edges, a fraction of the width.
  const val SAFE_X = 0.08

  // The title safe inset from the top and bottom edges, a fraction of the height.
  const val SAFE_Y = 0.08

  // The distance from one line's top to the next, in font dots. A glyph is seven dots tall.
  const val LINE_PITCH_DOTS = 10

  // The gap after each glyph, in font dots.
  const val GLYPH_GAP_DOTS = 1

  // Blinks per second for the record dot and a low battery.
  const val BLINK_RATE = 1.0

  // The clock the display changes on, in microseconds. A change between its ticks waits for the next, so every
  // change lands on a time the ffmpeg route's animated PNG names exactly.
  const val STATE_CLOCK_MICROS = 20L

  // The frame rate a counter counts frames at when the output does not say.
  const val FALLBACK_FRAME_RATE = 30.0

  const val TEXT_COLOUR: Int = 0xFFF2F2F2.toInt()
  const val EDGE_COLOUR: Int = 0xFF000000.toInt()
  const val RECORD_COLOUR: Int = 0xFFE8231C.toInt()
}
