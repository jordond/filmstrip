package dev.jordond.filmstrip.compose.ui.component

// A browser reports a wheel in CSS pixels, a mouse wheel notch is a hundred of them, and Compose hands the figure on
// divided by the density. Measured in Chrome at densities 1 and 2.
internal actual fun wheelNotches(
  delta: Float,
  density: Float,
): Float = delta * density / CSS_PIXELS_PER_NOTCH

private const val CSS_PIXELS_PER_NOTCH = 100f
