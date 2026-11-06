package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSink

/**
 * Where this target has an export write its file.
 *
 * @return The sink handed to the export.
 */
internal expect fun exportSink(): MediaSink

/**
 * Hands a finished export to the user where the result screen alone would not leave them with a file.
 *
 * @param output The sink the export reported it wrote to.
 */
internal expect fun saveExport(output: MediaSink)

/**
 * Lets go of what a finished export holds once the session has moved past it.
 *
 * @param output The sink the export reported it wrote to.
 */
internal expect fun releaseExport(output: MediaSink)
