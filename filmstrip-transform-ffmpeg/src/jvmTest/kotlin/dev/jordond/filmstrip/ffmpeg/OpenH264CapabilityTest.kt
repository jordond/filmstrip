package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegEncoderNamed
import dev.jordond.filmstrip.ffmpeg.internal.process.PACKET_DROP_FILTER
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Reads the capabilities of a stand-in ffmpeg that lists `libopenh264` as its only video encoder, the way an LGPL
 * build on Linux does.
 */
class OpenH264CapabilityTest {
  private val directory = Files.createTempDirectory("filmstrip-openh264").toFile()
  private val encodes = File(directory, "encodes")
  private val runtime =
    FfmpegRuntime.unshared(FfmpegConfig(executablePath = ffmpeg().path, probePath = script("ffprobe", "exit 1").path))
  private val engine = FfmpegExportEngine(ComponentRegistry.Builder().build(), runtime)

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `a build with only openh264 offers H264 through it and no HDR`() =
    runTest {
      val capabilities = engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities

      val video = capabilities.video.single()
      video.codec shouldBe VideoCodec.H264
      video.encoderName shouldBe "libopenh264"
      video.isHardwareAccelerated shouldBe false
      capabilities.supportsHdrEncoding shouldBe false
    }

  @Test
  fun `the ladder is measured at the profile an export encodes at`() =
    runTest {
      engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>()

      val profile = checkNotNull(ffmpegEncoderNamed("libopenh264")?.profile)
      val rungs = encodes.readLines()
      rungs.shouldNotBeEmpty()
      rungs.forEach { it shouldContain "-c:v libopenh264 -profile:v $profile" }
    }

  // Answers the toolchain lookup, lists only libopenh264 and writes down every encode it is asked for.
  private fun ffmpeg(): File =
    script(
      "ffmpeg",
      "case \"$*\" in " +
        "*lavfi*) echo \"$*\" >> '${encodes.path}' ;; " +
        "*-bsfs*) printf '%s\\n' 'Bitstream filters:' '$PACKET_DROP_FILTER' ;; " +
        "*-encoders*) printf '%s\\n' 'Encoders:' ' ------' ' V....D libopenh264 OpenH264 H.264' ;; " +
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
