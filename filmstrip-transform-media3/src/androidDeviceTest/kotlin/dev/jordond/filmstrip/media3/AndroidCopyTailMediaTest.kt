package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a copy keeps of the last frame's length when the source's last frame lasts longer than the ones before it,
 * read off the written container against the source's own on every API level.
 */
class AndroidCopyTailMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aCopyKeepsTheLengthOfASourcesLongLastFrame() =
    runTest(timeout = TIMEOUT) {
      val short = File(probe.context.cacheDir, SHORT_COPY_SOURCE)
      writePictureEndingInsideItsLastFrame(probe.fixtureFile(CLIP), short, LAST_FRAME_LASTS)
      val source = MediaSource.of(short.path)
      val plan = probe.plan(Composition(listOf(Track(listOf(Clip(source))))), ExportSpec())
      withClue("an untouched source is meant to copy, blocked by ${plan.copyBlockedBy}") {
        plan.path shouldBe ExportPath.Copy
      }

      val copy = probe.export(plan)

      withClue("the copy's track length against the source's") {
        videoTrackLength(copy) shouldBe videoTrackLength(short)
      }
      withClue("the copy's sample times against the source's") {
        videoSampleTimes(copy) shouldBe videoSampleTimes(short)
      }
    }

  // The last frame is the source's second sync sample, so a snapped trim that opens on it copies that frame alone, and
  // its length has to be counted from where the copy opens.
  @Test
  fun aSnappedCopyOfJustTheLastFrameKeepsItsLength() =
    runTest(timeout = TIMEOUT) {
      val short = File(probe.context.cacheDir, SHORT_COPY_SOURCE)
      val lastFrame = writePictureEndingInsideItsLastFrame(probe.fixtureFile(CLIP), short, LAST_FRAME_LASTS)
      val clip =
        Clip(
          MediaSource.of(short.path),
          trim = TimeRange.of(lastFrame, lastFrame + LAST_FRAME_LASTS),
          snapWithin = 1.seconds,
        )
      val plan = probe.plan(Composition(listOf(Track(listOf(clip)))), ExportSpec())
      withClue("a snapped trim on an untouched source is meant to copy, blocked by ${plan.copyBlockedBy}") {
        plan.path shouldBe ExportPath.Copy
      }

      val copy = probe.export(plan)

      videoTrackLength(copy) shouldBe LAST_FRAME_LASTS
    }

  // A trim that starts inside the last frame, within snapWithin of the sync sample that frame is, opens the copy on that
  // sample, and the frame's length is still counted from there.
  @Test
  fun aSnappedCopyStartingInsideTheLastFrameKeepsItsLength() =
    runTest(timeout = TIMEOUT) {
      val short = File(probe.context.cacheDir, SHORT_COPY_SOURCE)
      val lastFrame = writePictureEndingInsideItsLastFrame(probe.fixtureFile(CLIP), short, LAST_FRAME_LASTS)
      val clip =
        Clip(
          MediaSource.of(short.path),
          trim = TimeRange.of(lastFrame + INSIDE_LAST_FRAME, lastFrame + LAST_FRAME_LASTS),
          snapWithin = 1.seconds,
        )
      val plan = probe.plan(Composition(listOf(Track(listOf(clip)))), ExportSpec())
      withClue("a snapped trim on an untouched source is meant to copy, blocked by ${plan.copyBlockedBy}") {
        plan.path shouldBe ExportPath.Copy
      }

      val copy = probe.export(plan)

      videoTrackLength(copy) shouldBe LAST_FRAME_LASTS
    }

  // A container declaring a track length shorter than its samples must not cost the copy the samples past it.
  @Test
  fun aCopyOfASourceDeclaringALengthShorterThanItsSamplesKeepsEverySample() =
    runTest(timeout = TIMEOUT) {
      val short = File(probe.context.cacheDir, SHORT_COPY_SOURCE)
      val declaring = File(probe.context.cacheDir, SHORT_DECLARED_SOURCE)
      writePictureEndingInsideItsLastFrame(probe.fixtureFile(CLIP), short, LAST_FRAME_LASTS)
      short.copyTo(declaring, overwrite = true)
      declareTrackLength(declaring, DECLARED_MS)
      val plan = probe.plan(Composition(listOf(Track(listOf(Clip(MediaSource.of(declaring.path)))))), ExportSpec())
      withClue("an untouched source is meant to copy, blocked by ${plan.copyBlockedBy}") {
        plan.path shouldBe ExportPath.Copy
      }

      val copy = probe.export(plan)

      videoSampleTimes(copy) shouldBe videoSampleTimes(declaring)
    }

  // Rewrites the duration of every tkhd and mdhd box in place, whichever box version carries it.
  private fun declareTrackLength(
    file: File,
    millis: Int,
  ) {
    val bytes = file.readBytes()
    for (type in listOf("tkhd", "mdhd")) {
      val tag = type.toByteArray()
      for (at in 0..bytes.size - 4) {
        if (!tag.indices.all { bytes[at + it] == tag[it] }) continue
        val wide = bytes[at + 4].toInt() == 1
        if (type == "tkhd") {
          // In movie units, after the version, two times, the track id and a reserved word.
          if (wide) writeLong(bytes, at + 32, millis.toLong()) else writeInt(bytes, at + 24, millis.toLong())
        } else {
          val scale = readInt(bytes, if (wide) at + 24 else at + 16)
          val value = millis.toLong() * scale / 1000
          if (wide) writeLong(bytes, at + 32, value) else writeInt(bytes, at + 20, value)
        }
      }
    }
    file.writeBytes(bytes)
  }

  private fun readInt(
    bytes: ByteArray,
    at: Int,
  ): Long = (0 until 4).fold(0L) { total, i -> (total shl 8) or (bytes[at + i].toLong() and 0xFF) }

  private fun writeInt(
    bytes: ByteArray,
    at: Int,
    value: Long,
  ) {
    for (i in 0 until 4) bytes[at + i] = (value shr (8 * (3 - i))).toByte()
  }

  private fun writeLong(
    bytes: ByteArray,
    at: Int,
    value: Long,
  ) {
    for (i in 0 until 8) bytes[at + i] = (value shr (8 * (7 - i))).toByte()
  }

  private companion object {
    const val SHORT_DECLARED_SOURCE = "android_copy_tail_declared.mp4"
    const val DECLARED_MS = 100
    val INSIDE_LAST_FRAME = 20.milliseconds
    const val SHORT_COPY_SOURCE = "android_copy_tail_source.mp4"

    // Longer than the 1/30 s frames before it, and not a whole number of milliseconds.
    val LAST_FRAME_LASTS = 50.milliseconds + 111.microseconds
  }
}
