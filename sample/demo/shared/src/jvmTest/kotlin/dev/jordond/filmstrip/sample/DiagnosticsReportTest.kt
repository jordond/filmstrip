package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripVersion
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * What leaves the device, and what must not.
 */
class DiagnosticsReportTest {
  private val recorder = DiagnosticsRecorder()
  private val state =
    SampleAppState(
      filmstrip = Filmstrip(),
      recorder = recorder,
      scope = CoroutineScope(Dispatchers.Unconfined),
    )

  @Test
  fun `carries the version and the session log`() {
    recorder.record("export.failed", "error" to "NoEncoder")

    val report = state.diagnosticsReport()

    report.markdown shouldContain FilmstripVersion.name
    report.markdown shouldContain "export.failed"
    report.json shouldContain "export.failed"
  }

  @Test
  fun `never repeats the path of a picked file`() {
    state.onPicked(MediaSource.of("/Users/someone/Movies/holiday in crete.mp4"), "holiday in crete.mp4")

    val report = state.diagnosticsReport()

    report.markdown shouldNotContain "holiday"
    report.json shouldNotContain "holiday"
    report.json shouldNotContain "/Users/someone"
    report.markdown shouldContain ".mp4"
  }

  @Test
  fun `names the sample clip a session started from`() {
    val preset = samplePresets.first()
    state.onPicked(MediaSource.of("/tmp/${preset.fileName}"), preset.name, preset)

    val report = state.diagnosticsReport()

    report.markdown shouldContain preset.name
    report.markdown shouldContain preset.fileName
  }

  @Test
  fun `keeps the log inside its limit`() {
    val small = DiagnosticsRecorder(limit = 3)
    repeat(10) { index -> small.record("event.$index") }

    small.events shouldHaveSize 3
    small.events.map { it.label } shouldContain "event.9"
    small.events.map { it.label } shouldNotContain "event.0"
  }
}
