package dev.jordond.filmstrip.sample

import io.github.vinceglb.filekit.PlatformFile

/**
 * Writes a report next to the app and says where it went.
 *
 * The markdown goes into an issue and the json replays the edit, so both are written together and
 * a caller that only wants the text still copies it from the pane instead.
 *
 * @param report The report to write.
 * @return The markdown file, ready to hand to a share sheet, or null where the platform hands it
 *   straight to the user, which is what a browser download does.
 */
public expect suspend fun writeDiagnostics(report: DiagnosticsReport): PlatformFile?
