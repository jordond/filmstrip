package dev.jordond.filmstrip.sample.ui

import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry.withPlainText(text)
