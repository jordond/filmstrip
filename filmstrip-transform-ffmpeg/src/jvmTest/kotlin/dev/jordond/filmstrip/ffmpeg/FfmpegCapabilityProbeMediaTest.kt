package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FFMPEG_ENCODERS
import dev.jordond.filmstrip.ffmpeg.internal.measureCapabilities
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.transform.internal.RESOLUTION_LADDER
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The encoder ladder measured against the real toolchain, with the encoder list narrowed so each case
 * says which encoders were there to probe.
 */
class FfmpegCapabilityProbeMediaTest {
  private val runtime = FfmpegRuntime.unshared(FfmpegConfig())

  @Test
  fun `an encoder the build carries is measured to a rung of the ladder`() =
    runTest {
      val capabilities = measured(narrowed(setOf("libx264", "aac")))

      val video = capabilities.video.single()
      video.codec shouldBe VideoCodec.H264
      video.encoderName shouldBe "libx264"
      RESOLUTION_LADDER shouldContain video.maxSize
      video.isHardwareAccelerated shouldBe false
      video.maxFrameRate shouldBe null
      video.maxBitrate shouldBe null
      capabilities.audio.map { it.codec } shouldBe listOf(AudioCodec.Aac)
      capabilities.concurrentSessionBudget shouldBe null
    }

  // H.264 is not on the HDR ladder, so a build carrying only it has no encoder an HDR export opens.
  @Test
  fun `a build with no encoder on the hdr ladder cannot encode hdr`() =
    runTest {
      measured(narrowed(setOf("libx264"))).supportsHdrEncoding shouldBe false
    }

  // A build without libx265 still lists it here, so the probe has to open it to find it cannot encode anything.
  @Test
  fun `hdr is answered by the encoder an hdr export opens`() =
    runTest {
      val carried = toolchain().hasEncoder("libx265")

      val capabilities = measured(narrowed(setOf("libx265")))

      if (carried) {
        capabilities.video.single().encoderName shouldBe "libx265"
        capabilities.supportsHdrEncoding shouldBe true
      } else {
        capabilities.video.shouldBeEmpty()
        capabilities.supportsHdrEncoding shouldBe false
      }
    }

  // Audio is read off the encoder list rather than probed, so it is the one answer a binary that will
  // not start still gives.
  @Test
  fun `a binary that will not start encodes no video and no hdr`() =
    runTest {
      val everything =
        FFMPEG_ENCODERS.values
          .flatten()
          .map { it.name }
          .toSet() + setOf("aac", "alac")
      val broken = Toolchain(MISSING, MISSING, toolchain().version, emptySet(), everything)

      val capabilities = measured(broken)

      capabilities.video.shouldBeEmpty()
      capabilities.supportsHdrEncoding shouldBe false
      capabilities.audio.map { it.codec } shouldBe listOf(AudioCodec.Aac, AudioCodec.Alac)
    }

  private suspend fun measured(toolchain: Toolchain): DeviceCapabilities =
    runtime.measureCapabilities(toolchain).shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities

  private suspend fun toolchain(): Toolchain =
    runtime.toolchain().shouldBeInstanceOf<ToolchainResult.Available>().toolchain

  private suspend fun narrowed(encoders: Set<String>): Toolchain {
    val real = toolchain()
    return Toolchain(real.ffmpeg, real.ffprobe, real.version, real.filters, encoders)
  }

  private companion object {
    const val MISSING = "/nonexistent/ffmpeg"
  }
}
