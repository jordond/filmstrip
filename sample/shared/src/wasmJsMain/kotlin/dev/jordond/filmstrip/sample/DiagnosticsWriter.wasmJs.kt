@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample

// There is no filesystem, so each half is published as a blob and handed to the browser's own
// download, which is the only way a page gives a user a file.
public actual suspend fun writeDiagnostics(report: DiagnosticsReport): String? {
  download("filmstrip-report.md", report.markdown, "text/markdown")
  download("filmstrip-report.json", report.json, "application/json")
  return null
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
