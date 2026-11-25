package dev.jordond.filmstrip.player

import android.graphics.Bitmap
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.inspector.MediaExtractorCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.test.eightBitFrameCovering
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exports the two slot seam cut of the moving source again and again and checks every slot around the seam holds the
 * source frame it should.
 *
 * A slot holding any other frame is reported with the source frame it does hold, and its file is kept in the test's
 * output directory. Where [staleSeamExportsHappen], an export with a stale slot is written once more and only fails
 * when that one is stale too. The `repeats` runner argument sets how many exports run, and `arm` picks the edit:
 * `seam` cuts the source at 475 ms, `plain` lays the same second as one clip.
 */
class AndroidSeamExportRepeatMediaTest {
  @Test
  fun everyExportHoldsTheSourceFrameOnEverySlotAroundTheSeam() =
    contractTest(timeout = 6.hours) {
      val source = AndroidShortClips.movingSource().shouldBeInstanceOf<MediaSource.Path>()
      val references = (0 until REFERENCE_FRAMES).map { lumaOf(eightBitFrameCovering(File(source.path), atSlot(it))) }
      val edit = editFor(source, argument("arm") ?: "seam")
      val repeats = argument("repeats")?.toInt() ?: DEFAULT_REPEATS
      val hits = mutableListOf<String>()

      repeat(repeats) { run ->
        val first = missesOf(export(edit), references, run)
        val misses =
          if (first.isNotEmpty() &&
            staleSeamExportsHappen
          ) {
            missesOf(export(edit), references, run)
          } else {
            first
          }
        if (misses !== first) first.forEach { println("Stale on the emulator, exported again: $it") }
        hits += misses
      }

      withClue("of $repeats exports") { hits.shouldBeEmpty() }
    }

  private fun missesOf(
    file: File,
    references: List<IntArray>,
    run: Int,
  ): List<String> {
    val misses = mutableListOf<String>()
    val samples = videoSampleTimesOf(file)
    if (samples.size != EXPECTED_SAMPLES) {
      misses += "SEAMREPEAT run=$run samples=${samples.size} times=${samples.map { it.inWholeMicroseconds }}"
    }
    for (slot in READ_SLOTS) {
      val luma = lumaOf(eightBitFrameCovering(file, atSlot(slot)))
      val matches = references.indices.minBy { differenceOf(luma, references[it]) }
      if (matches != slot.toInt()) {
        val near =
          samples.filter { abs((it - outputSlotTime(slot, FIXTURE_FRAME_RATE)).inWholeMicroseconds) < HALF_SLOT_US * 4 }
        misses += "SEAMREPEAT run=$run slot=$slot matches=$matches near=${near.map { it.inWholeMicroseconds }}"
      }
    }
    if (misses.isEmpty()) file.delete() else keep(file, run)
    return misses
  }

  private fun editFor(
    source: MediaSource,
    arm: String,
  ): Composition {
    val clips =
      when (arm) {
        "plain" -> listOf(Clip(source, TimeRange.of(Duration.ZERO, END)))
        else -> listOf(Clip(source, TimeRange.of(Duration.ZERO, SEAM)), Clip(source, TimeRange.of(SEAM, END)))
      }
    return Composition(listOf(Track(clips)))
  }

  private fun videoSampleTimesOf(file: File): List<Duration> {
    val extractor = MediaExtractorCompat().apply { setDataSource(Uri.fromFile(file), 0L) }
    try {
      val track =
        (0 until extractor.trackCount).first {
          extractor
            .getTrackFormat(it)
            .getString(MediaFormat.KEY_MIME)
            .orEmpty()
            .startsWith("video/")
        }
      extractor.selectTrack(track)
      val times = mutableListOf<Duration>()
      while (extractor.sampleTrackIndex >= 0) {
        times += extractor.sampleTime.microseconds
        extractor.advance()
      }
      return times.sorted()
    } finally {
      extractor.release()
    }
  }

  private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

  private fun keep(
    file: File,
    run: Int,
  ) {
    val directory = argument("additionalTestOutputDir")?.let(::File) ?: contractContext().getExternalFilesDir(null)
    file.copyTo(File(directory, "seam-repeat-$run-${file.name}"), overwrite = true)
    file.delete()
  }

  // Half a slot in, so the covering sample is the slot's own whichever way its time rounds.
  private fun atSlot(slot: Number): Duration = outputSlotTime(slot.toLong(), FIXTURE_FRAME_RATE) + HALF_SLOT

  private fun lumaOf(frame: Bitmap): IntArray {
    val width = frame.width / STEP
    val height = frame.height / STEP
    return IntArray(width * height) { index ->
      val color = frame.getPixel(index % width * STEP, index / width * STEP)
      (color shr RED_SHIFT and BYTE) + (color shr GREEN_SHIFT and BYTE) * 2 + (color and BYTE)
    }
  }

  private fun differenceOf(
    a: IntArray,
    b: IntArray,
  ): Long = a.indices.sumOf { abs(a[it] - b[it]).toLong() }

  private companion object {
    const val DEFAULT_REPEATS = 3
    const val EXPECTED_SAMPLES = 30
    const val HALF_SLOT_US = 16_666L
    const val REFERENCE_FRAMES = 30
    const val STEP = 4
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val BYTE = 0xFF
    val READ_SLOTS = 9L..18L
    val SEAM = 475.milliseconds
    val END = 1_000.milliseconds
    val HALF_SLOT = 16_666.microseconds
  }
}
