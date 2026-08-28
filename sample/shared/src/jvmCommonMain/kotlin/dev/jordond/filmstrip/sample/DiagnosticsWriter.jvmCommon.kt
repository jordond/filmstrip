package dev.jordond.filmstrip.sample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Android points java.io.tmpdir at the app's own cache directory, which is also where FileKit's
// share sheet can read from, so one implementation covers the phone and the desktop.
public actual suspend fun writeDiagnostics(report: DiagnosticsReport): String? = withContext(Dispatchers.IO) {
  val directory = File(System.getProperty("java.io.tmpdir"), REPORT_DIRECTORY).apply { mkdirs() }
  val markdown = File(directory, "$REPORT_NAME.md")

  markdown.writeText(report.markdown)
  File(directory, "$REPORT_NAME.json").writeText(report.json)

  markdown.absolutePath
}

private const val REPORT_DIRECTORY = "filmstrip-diagnostics"
private const val REPORT_NAME = "filmstrip-report"
