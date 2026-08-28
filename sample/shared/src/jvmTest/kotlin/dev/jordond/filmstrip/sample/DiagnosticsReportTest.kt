package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripVersion
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What leaves the device, and what must not.
 */
class DiagnosticsReportTest {
  private val recorder = DiagnosticsRecorder()
  private val state = SampleAppState(
    filmstrip = Filmstrip(),
    recorder = recorder,
    scope = CoroutineScope(Dispatchers.Unconfined),
  )

  @Test
  fun `carries the version and the session log`() {
    recorder.record("export.failed", "error" to "NoEncoder")

    val report = state.diagnosticsReport()

    assertContains(report.markdown, FilmstripVersion.name)
    assertContains(report.markdown, "export.failed")
    assertContains(report.json, "export.failed")
  }

  @Test
  fun `never repeats the path of a picked file`() {
    state.onPicked(MediaSource.of("/Users/someone/Movies/holiday in crete.mp4"), "holiday in crete.mp4")

    val report = state.diagnosticsReport()

    assertFalse(report.markdown.contains("holiday"), "the report names the file the user picked")
    assertFalse(report.json.contains("holiday"), "the json names the file the user picked")
    assertFalse(report.json.contains("/Users/someone"), "the json carries the path it came from")
    assertContains(report.markdown, ".mp4")
  }

  @Test
  fun `names the sample clip a session started from`() {
    val preset = samplePresets.first()
    state.onPicked(MediaSource.of("/tmp/${preset.fileName}"), preset.name, preset)

    val report = state.diagnosticsReport()

    assertContains(report.markdown, preset.name)
    assertContains(report.markdown, preset.fileName)
  }

  @Test
  fun `keeps the log inside its limit`() {
    val small = DiagnosticsRecorder(limit = 3)
    repeat(10) { index -> small.record("event.$index") }

    assertTrue(small.events.size == 3)
    assertContains(small.events.map { it.label }, "event.9")
    assertFalse(small.events.map { it.label }.contains("event.0"))
  }
}
