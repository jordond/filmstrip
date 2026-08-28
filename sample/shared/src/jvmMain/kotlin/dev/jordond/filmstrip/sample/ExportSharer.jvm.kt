package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable

// Desktop has no share sheet. The result screen's player card opens the file instead.
@Composable
public actual fun rememberExportSharer(): ((path: String) -> Unit)? = null
