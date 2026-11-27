package dev.jordond.filmstrip.compose.ui.component

// The platform reports one unit for one wheel notch, whatever the density.
internal actual fun wheelNotches(
  delta: Float,
  density: Float,
): Float = delta
