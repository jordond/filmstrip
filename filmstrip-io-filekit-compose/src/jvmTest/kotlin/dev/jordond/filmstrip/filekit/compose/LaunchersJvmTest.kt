package dev.jordond.filmstrip.filekit.compose

import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * What the desktop launchers are before anyone clicks anything. Nothing here opens a dialog, so
 * only the remembered value is under test.
 */
class LaunchersJvmTest {
  @Test
  fun `there is no share sheet on the desktop`() =
    runTest {
      var launcher: ShareLauncher? = null
      val runtime = ComposeRuntime(this)

      try {
        runtime.setContent { launcher = rememberShareLauncher() }

        launcher shouldBe null
      } finally {
        runtime.dispose()
      }
    }

  @Test
  fun `a media picker composes`() =
    runTest {
      var launcher: PickerResultLauncher? = null
      val runtime = ComposeRuntime(this)

      try {
        runtime.setContent { launcher = rememberMediaPickerLauncher { } }

        (launcher != null) shouldBe true
      } finally {
        runtime.dispose()
      }
    }

  @Test
  fun `an image picker composes`() =
    runTest {
      var launcher: PickerResultLauncher? = null
      val runtime = ComposeRuntime(this)

      try {
        runtime.setContent { launcher = rememberImagePickerLauncher { } }

        (launcher != null) shouldBe true
      } finally {
        runtime.dispose()
      }
    }

  @Test
  fun `an export saver composes`() =
    runTest {
      var launcher: ExportSaverLauncher? = null
      var reported: MediaSink? = null
      val runtime = ComposeRuntime(this)

      try {
        runtime.setContent { launcher = rememberExportSaverLauncher { sink -> reported = sink } }

        (launcher != null) shouldBe true
        reported shouldBe null
      } finally {
        runtime.dispose()
      }
    }
}
