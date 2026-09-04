package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.SYNC_SAMPLE_WINDOW
import dev.jordond.filmstrip.ffmpeg.internal.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.parseSyncSample
import dev.jordond.filmstrip.ffmpeg.internal.syncSampleArguments
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

// A cut in the middle of the listing rather than on either end, since the first and last entries
// agree with a parser that took the earliest and one that took the latest.
private val CUT = 4_500.milliseconds

class SyncSampleTest {
  // ffprobe writes a trailing comma after the first entry and lists every sample it read, the ones
  // past the cut included, so neither the punctuation nor the tail may reach the answer.
  @Test
  fun `takes the latest sync sample at or before the cut`() {
    val listing =
      """
      0.000000,
      0.966667
      3.866667
      4.833333
      """.trimIndent()

    parseSyncSample(listing, CUT) shouldBe 3.866667.seconds
  }

  // A cut already on a sync sample snaps nowhere, and reading it as the sample before would move a
  // window that had no reason to move.
  @Test
  fun `takes the cut itself when a sync sample sits on it`() {
    parseSyncSample("3.000000\n4.500000\n", CUT) shouldBe CUT
  }

  // What a source with no video stream lists, and what a cut with nothing behind it lists.
  @Test
  fun `names nothing when the listing holds no sample the cut can reach`() {
    parseSyncSample("", CUT) shouldBe null
    parseSyncSample("4.833333\n", CUT) shouldBe null
  }

  @Test
  fun `reads a window ahead of the cut rather than the whole file`() {
    val cut = SYNC_SAMPLE_WINDOW * 2

    val interval = syncSampleArguments(toolchain(), PATH, cut).after("-read_intervals")

    interval shouldBe "${printed(SYNC_SAMPLE_WINDOW)}%${printed(cut)}"
  }

  // Nothing sits before the start of a file, and ffprobe reads a negative interval as an offset
  // from where it already is rather than as a time.
  @Test
  fun `opens the window at zero for a cut closer to the start than the window is wide`() {
    val cut = SYNC_SAMPLE_WINDOW / 2

    val interval = syncSampleArguments(toolchain(), PATH, cut).after("-read_intervals")

    interval shouldBe "${printed(Duration.ZERO)}%${printed(cut)}"
  }

  private fun printed(value: Duration): String = formatSeconds(value.toDouble(DurationUnit.SECONDS))

  private fun List<String>.after(flag: String): String = this[indexOf(flag) + 1]

  private fun toolchain() =
    Toolchain(
      ffmpeg = "/usr/bin/ffmpeg",
      ffprobe = "/usr/bin/ffprobe",
      version = FfmpegVersion("ffmpeg version 9.0.1", 9, 0),
      filters = emptySet(),
      encoders = emptySet(),
    )

  private companion object {
    const val PATH = "/clips/long.mp4"
  }
}
