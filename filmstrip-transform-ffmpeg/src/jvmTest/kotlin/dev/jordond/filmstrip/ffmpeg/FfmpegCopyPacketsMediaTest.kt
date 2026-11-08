package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.probe.UNLISTED_PACKETS
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A copy that ends before its source does, planned through an ffprobe that reads the source but cannot list its
 * packets.
 */
class FfmpegCopyPacketsMediaTest {
  private val fixture = FfmpegExportFixture()

  // Nothing is left to count the video by, so the copy is refused naming why rather than run to its input bound.
  @Test
  fun `refuses a copy ending before its source when its packets cannot be read`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val directory = Files.createTempDirectory("filmstrip-unlisted").toFile()
      val ffprobe =
        File(directory, "ffprobe").apply {
          writeText("#!/bin/sh\ncase \"$*\" in *packet=pts_time*) exit 1 ;; esac\nexec '$hostFfprobe' \"$@\"\n")
          setExecutable(true)
        }
      val filmstrip = Filmstrip { ffmpegBackend(FfmpegConfig(executablePath = hostFfmpeg, probePath = ffprobe.path)) }
      val source = MediaSource.of(fixture.long.absolutePath)
      val composition = compositionOf { clip(source) { trim(TimeRange.of(Duration.ZERO, WINDOW_END)) } }

      try {
        val reasons = filmstrip.plan(composition, ExportSpec()).shouldBeInstanceOf<Verdict.Incapable>().reasons
        val refusal = reasons.single().shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
        refusal.message shouldBe UNLISTED_PACKETS
      } finally {
        directory.deleteRecursively()
      }
    }

  private companion object {
    // Inside the long fixture's twelve seconds, so the copy ends before its source does.
    val WINDOW_END = 1_010.milliseconds
  }
}
