package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.PACKET_DROP_FILTER
import dev.jordond.filmstrip.transform.internal.FLOOR_LADDER
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Reads the capabilities of a stand-in ffmpeg whose only encoder refuses every frame under one rung of
 * [FLOOR_LADDER], the way libx265 refuses a frame under 16 pixels a side.
 */
class FfmpegFloorProbeTest {
  private val directory = Files.createTempDirectory("filmstrip-floor").toFile()
  private val encodes = File(directory, "encodes")
  private val floor = FLOOR_LADDER[FLOOR_LADDER.size / 2]
  private val refused = FLOOR_LADDER.takeWhile { it != floor }
  private val runtime =
    FfmpegRuntime.unshared(FfmpegConfig(executablePath = ffmpeg().path, probePath = script("ffprobe", "exit 1").path))
  private val engine = FfmpegExportEngine(ComponentRegistry.Builder().build(), runtime)

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `the smallest frame is the first rung the encoder opens at`() =
    runTest {
      val capabilities = engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities

      capabilities.video.single().minSize shouldBe floor
    }

  @Test
  fun `the walk climbs from the bottom of the ladder and stops at the first rung that opens`() =
    runTest {
      engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>()

      val asked = encodes.readLines().map { it.substringAfter("color=s=").substringBefore(":") }
      val walk = (refused + floor).map { "${it.width}x${it.height}" }
      asked shouldContainInOrder walk
      asked.count { it == "${floor.width}x${floor.height}" } shouldBe 1
    }

  // Answers the toolchain lookup, lists only libx264, writes down every encode and fails the ones under the floor.
  private fun ffmpeg(): File =
    script(
      "ffmpeg",
      "case \"$*\" in " +
        "*lavfi*) echo \"$*\" >> '${encodes.path}'; case \"$*\" in " +
        refused.joinToString("") { "*color=s=${it.width}x${it.height}:*) exit 1 ;; " } +
        "esac ;; " +
        "*-bsfs*) printf '%s\\n' 'Bitstream filters:' '$PACKET_DROP_FILTER' ;; " +
        "*-encoders*) printf '%s\\n' 'Encoders:' ' ------' ' V....D libx264 H.264' ;; " +
        "*-version*) printf '%s\\n' 'ffmpeg version 8.1.2 Copyright (c) 2000-2025 the FFmpeg developers' ;; esac",
    )

  private fun script(
    name: String,
    body: String,
  ): File =
    File(directory, name).apply {
      writeText("#!/bin/sh\n$body\n")
      setExecutable(true)
    }
}
