@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.sample

import kotlinx.cinterop.ExperimentalForeignApi

import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile

public actual suspend fun writeDiagnostics(report: DiagnosticsReport): String? {
  val directory = NSTemporaryDirectory()
  val markdown = (directory as NSString).stringByAppendingPathComponent("$REPORT_NAME.md")
  val json = (directory as NSString).stringByAppendingPathComponent("$REPORT_NAME.json")

  val wrote = (report.markdown as NSString).writeToFile(markdown, true, NSUTF8StringEncoding, null)
  (report.json as NSString).writeToFile(json, true, NSUTF8StringEncoding, null)

  return if (wrote) markdown else null
}

private const val REPORT_NAME = "filmstrip-report"
