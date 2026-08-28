package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable

// A browser export is a blob url with no file behind it, so there is nothing to share.
@Composable
public actual fun rememberExportSharer(): ((path: String) -> Unit)? = null
