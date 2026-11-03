package dev.jordond.filmstrip.webcodecs.internal

/**
 * A browser-side failure with a message worth showing. Anything else that escapes the pipeline is
 * reported with whatever the platform said.
 */
internal class BrowserExportFailure(
  message: String,
) : Exception(message)
