package dev.jordond.filmstrip.sample.ui

import androidx.compose.ui.Modifier

/**
 * Keeps a drag starting on this element from being taken as the system back gesture, on a target that has one.
 *
 * @return This modifier with the exclusion added.
 */
internal expect fun Modifier.excludeBackGesture(): Modifier
