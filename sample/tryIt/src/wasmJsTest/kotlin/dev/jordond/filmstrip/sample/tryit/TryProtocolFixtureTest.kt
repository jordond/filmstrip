@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Holds the app's end of the protocol to `protocol.fixture.json`, the same file `tryProtocol.test.ts` reads.
 */
class TryProtocolFixtureTest {
  private val clip = 12_000.milliseconds

  @Test
  fun `every control in the fixture decodes through the browser's parser`() {
    fixtureEntries("control").map { decodeControl(it) } shouldBe
      listOf(
        Control.SetSteps(Steps(trim = true, grade = false, title = true)),
        Control.Export,
        Control.Save,
        Control.Cancel,
        Control.Retry,
      )
  }

  @Test
  fun `every status in the fixture is what the app encodes`() {
    val statuses =
      listOf(
        TryStatus(),
        TryStatus(
          position = 5250.milliseconds,
          duration = clip,
          trimRange = TRIM_RANGE,
          export = ExportState.Running(0.37f),
        ),
        TryStatus(
          position = 7000.milliseconds,
          duration = clip,
          export = ExportState.Done(MediaSink.ofUri("blob:written")),
          error = "The file could not be \"read\": a\\b.",
        ),
        TryStatus(
          position = 7000.milliseconds,
          duration = clip,
          exportRefusal = "This device can't encode video, so it can preview but not export.",
        ),
        TryStatus(
          position = 7000.milliseconds,
          duration = clip,
          awaitingTap = true,
        ),
      )
    fixtureEntries("status") shouldBe statuses.map(::encodeStatus)
  }

  @Test
  fun `every ready in the fixture is what the app encodes`() {
    fixtureEntries("ready") shouldBe listOf(encodeReady())
  }

  @Test
  fun `what the fixture lists as ignored decodes to nothing`() {
    val ignored = fixtureStrings("ignored")
    ignored shouldHaveSize 8
    ignored.forEach { decodeControl(it).shouldBeNull() }
  }

  @Test
  fun `a message past the limit decodes to nothing`() {
    val long = "{\"type\":\"control\",\"name\":\"save\",\"pad\":\"${"a".repeat(MAX_WIRE_CHARS)}\"}"
    decodeControl(long).shouldBeNull()
  }
}

// Each entry of one list, written back out by the browser so the key order and spacing are the fixture's own.
private fun fixtureEntries(list: String): List<String> =
  (0 until entryCount(PROTOCOL_FIXTURE, list)).map { entryJson(PROTOCOL_FIXTURE, list, it).toString() }

private fun fixtureStrings(list: String): List<String> =
  (0 until entryCount(PROTOCOL_FIXTURE, list)).map { entryText(PROTOCOL_FIXTURE, list, it).toString() }

private fun entryCount(
  fixture: String,
  list: String,
): Int = js("JSON.parse(fixture)[list].length")

private fun entryJson(
  fixture: String,
  list: String,
  index: Int,
): JsString = js("JSON.stringify(JSON.parse(fixture)[list][index])")

private fun entryText(
  fixture: String,
  list: String,
  index: Int,
): JsString = js("JSON.parse(fixture)[list][index]")
