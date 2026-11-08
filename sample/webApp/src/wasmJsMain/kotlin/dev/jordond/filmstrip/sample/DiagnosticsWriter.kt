@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample

/**
 * Hands a report to the browser as two downloads.
 *
 * The markdown goes into an issue and the json replays the edit, so both are written together and a caller that only
 * wants the text still copies it from the pane instead. Each half is published as a blob and clicked through an
 * anchor, which is how a page gives a user a file.
 *
 * @param report The report to download.
 */
public fun writeDiagnostics(report: DiagnosticsReport) {
  download("filmstrip-report.md", report.markdown, "text/markdown")
  download("filmstrip-report.json", report.json, "application/json")
}

private fun download(
  name: String,
  content: String,
  type: String,
): Unit =
  js(
    """
    {
      const url = URL.createObjectURL(new Blob([content], { type: type }))
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = name
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    }
    """,
  )
