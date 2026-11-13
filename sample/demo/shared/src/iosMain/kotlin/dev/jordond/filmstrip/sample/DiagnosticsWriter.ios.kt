@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.jordond.filmstrip.sample

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile

public actual suspend fun writeDiagnostics(report: DiagnosticsReport): PlatformFile? {
  val directory = NSTemporaryDirectory()
  val markdown = NSString.create(string = directory).stringByAppendingPathComponent("$REPORT_NAME.md")
  val json = NSString.create(string = directory).stringByAppendingPathComponent("$REPORT_NAME.json")

  val wrote = NSString.create(string = report.markdown).writeToFile(markdown, true, NSUTF8StringEncoding, null)
  NSString.create(string = report.json).writeToFile(json, true, NSUTF8StringEncoding, null)

  return if (wrote) PlatformFile(NSURL.fileURLWithPath(markdown)) else null
}

private const val REPORT_NAME = "filmstrip-report"
