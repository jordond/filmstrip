package dev.jordond.filmstrip.diagnostics

import dev.jordond.filmstrip.InternalFilmstripApi
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test

@OptIn(InternalFilmstripApi::class)
class RedactionTest {
  @Test
  fun aLocationKeepsItsExtensionAndHashesTheRest() {
    val redacted = redactLocation("/storage/emulated/0/DCIM/holiday.mp4")

    redacted shouldStartWith "clip-"
    redacted shouldEndWith ".mp4"
    redactLocation("/storage/emulated/0/DCIM/holiday.mp4") shouldBe redacted
    redactLocation("/storage/emulated/0/DCIM/other.mp4") shouldNotBe redacted
    redactLocation("content://media/external/video/media/42") shouldBe
      "clip-${"content://media/external/video/media/42".hashCode().toUInt().toString(16)}"
  }

  @Test
  fun everyKindOfLocationInsideTextIsRewritten() {
    val path = "/data/user/0/app/files/clip.mov"
    val uri = "content://com.android.providers/document/video%3A42"
    val home = "~/Movies/clip.mkv"
    val windows = """C:\Users\someone\clip.mp4"""

    redactLocations("Open $path failed: ENOENT, then ($uri) and '$home' and $windows.") shouldBe
      "Open ${redactLocation(path)} failed: ENOENT, then (${redactLocation(uri)}) and " +
      "'${redactLocation(home)}' and ${redactLocation(windows)}."
  }

  @Test
  fun textWithNoLocationIsLeftAlone() {
    val text = "Decoder c2.goldfish.h264.decoder failed on video/avc at 1920x1080, a/b/c and file.mp4 untouched."

    redactLocations(text) shouldBe text
  }

  @Test
  fun aPathAfterAKeyIsRewrittenAndItsKeyKept() {
    redactLocations("uri=/sdcard/a.mp4, res=1x1") shouldBe "uri=${redactLocation("/sdcard/a.mp4")}, res=1x1"
  }

  @Test
  fun aCauseChainReadsOneLevelPerLineOutermostFirst() {
    val root = IllegalStateException("queueInputBuffer failed for /sdcard/a.mp4")
    val middle = RuntimeException("Decoder failed", root)
    val top = IllegalArgumentException(null, middle)

    causeChainOf(top) shouldBe
      listOf(
        "IllegalArgumentException",
        "RuntimeException: Decoder failed",
        "IllegalStateException: queueInputBuffer failed for ${redactLocation("/sdcard/a.mp4")}",
      )
  }

  @Test
  fun aCauseChainStopsAtEightLevels() {
    var throwable: Throwable = RuntimeException("level 0")
    (1..20).forEach { throwable = RuntimeException("level $it", throwable) }

    causeChainOf(throwable).size shouldBe 8
  }
}
