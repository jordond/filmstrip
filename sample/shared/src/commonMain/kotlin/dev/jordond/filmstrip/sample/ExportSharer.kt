package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable

/**
 * Hands the exported file to the platform's share sheet.
 *
 * Null where there is no share sheet to hand it to, which is every target that is not a phone. The
 * result screen hides its share button rather than showing one that cannot do anything.
 */
@Composable
public expect fun rememberExportSharer(): ((path: String) -> Unit)?
