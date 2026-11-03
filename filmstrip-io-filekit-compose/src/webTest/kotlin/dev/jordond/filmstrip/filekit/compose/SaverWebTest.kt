package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The browser saver, which opens nothing and reports the download name straight back on launch.
 */
class SaverWebTest {
  @Test
  fun `a launch reports the download name at once`() =
    runTest {
      val reported = mutableListOf<MediaSink?>()
      var errors = 0
      withSaver(onError = { errors++ }, onResult = { reported += it }) { launcher ->
        launcher.launch("holiday")
        launcher.launch("holiday", defaultExtension = "webm")
      }

      reported shouldBe listOf(MediaSink.Path("holiday.mp4"), MediaSink.Path("holiday.webm"))
      errors shouldBe 0
    }

  @Test
  fun `a launch with no extension downloads under the bare name`() =
    runTest {
      val reported = mutableListOf<MediaSink?>()
      withSaver(onResult = { reported += it }) { launcher -> launcher.launch("holiday", defaultExtension = null) }

      reported shouldBe listOf(MediaSink.Path("holiday"))
    }

  @Test
  fun `a recomposed caller hears the launch on its latest callback`() =
    runTest {
      val first = mutableListOf<MediaSink?>()
      val second = mutableListOf<MediaSink?>()
      var listening by mutableStateOf(1)
      val launchers = mutableListOf<ExportSaverLauncher>()
      val runtime = ComposeRuntime(this)

      try {
        runtime.setContent {
          val current = listening
          launchers += rememberExportSaverLauncher { sink -> if (current == 1) first += sink else second += sink }
        }
        listening = 2
        runtime.settle()

        launchers.last().launch("holiday")

        withClue("recomposing built a second launcher") { launchers.last() shouldBeSameInstanceAs launchers.first() }
        first shouldBe emptyList()
        second shouldBe listOf(MediaSink.Path("holiday.mp4"))
      } finally {
        runtime.dispose()
      }
    }

  private fun TestScope.withSaver(
    onError: () -> Unit = {},
    onResult: (MediaSink?) -> Unit,
    block: (ExportSaverLauncher) -> Unit,
  ) {
    var launcher: ExportSaverLauncher? = null
    val runtime = ComposeRuntime(this)
    try {
      runtime.setContent { launcher = rememberExportSaverLauncher(onError = { onError() }, onResult = onResult) }
      block(withClue("the saver did not compose") { launcher.shouldNotBeNull() })
    } finally {
      runtime.dispose()
    }
  }
}
