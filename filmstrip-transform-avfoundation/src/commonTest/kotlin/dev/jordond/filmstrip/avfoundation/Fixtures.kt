package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.media.HdrTransfer
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The path of the fixture clip [name] inside the directory `FILMSTRIP_FIXTURES` names.
 *
 * Fails the calling test when the variable is unset or the clip is missing from that directory. Gradle sets it for both
 * Apple test tasks, and the iPhone runner sets it to the fixtures copied into the app bundle.
 */
internal fun fixture(name: String): String {
  val directory = assertNotNull(NSProcessInfo.processInfo.environment[FIXTURES] as? String, "$FIXTURES is unset")
  val path = "$directory/$name"
  assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path), "$FIXTURES is set but $path is missing")
  return path
}

// The path of the graded fixture clip carrying transfer, failing the same way fixture does.
internal fun hdrFixture(transfer: HdrTransfer): String = fixture(hdrFixtureName(transfer))

// The file name of the graded fixture clip carrying transfer, as it sits in the fixtures directory.
internal fun hdrFixtureName(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> HDR_FIXTURE
    HdrTransfer.Hlg -> HLG_FIXTURE
  }

internal const val HDR_FIXTURE = "apple_export_hdr.mp4"
internal const val HLG_FIXTURE = "apple_export_hdr_hlg.mp4"

private const val FIXTURES = "FILMSTRIP_FIXTURES"
