package dev.jordond.filmstrip.sample.ui

import androidx.compose.ui.platform.ClipEntry

/**
 * Wraps some text in a clip entry this target's clipboard accepts.
 *
 * @param text The text to put on the clipboard.
 * @return The entry to hand to `Clipboard.setClipEntry`.
 */
internal expect fun plainTextClip(text: String): ClipEntry
