package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.lowering.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.probe.copyPacketArguments
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseCopyPacket
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.transform.internal.copy.COPY_PACKET_MARGIN
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class CopyPacketsTest {
  // Read as ffprobe 9 prints the long fixture's packets, so the key flag, a packet with no time and the decode order
  // all reach the list as written.
  @Test
  fun `keeps the packets in decode order with their times and sync samples`() {
    val listing =
      """
      0.966667,K__
      1.100000,___
      N/A,___
      1.000000,___
      """.trimIndent()

    val packets = listing.lines().mapNotNull(::parseCopyPacket)

    packets.map { it.pts } shouldBe listOf(0.966667.seconds, 1.1.seconds, Duration.INFINITE, 1.seconds)
    packets.map { it.key } shouldBe listOf(true, false, false, false)
  }

  @Test
  fun `reads from the window's start to past its end`() {
    val from = 3_866_667.seconds / 1_000_000
    val to = 5_250.milliseconds

    val arguments = copyPacketArguments(toolchain(), "/a.mp4", from, to)

    arguments[arguments.indexOf("-read_intervals") + 1] shouldBe "${from.printed}%${(to + COPY_PACKET_MARGIN).printed}"
  }

  private val Duration.printed: String get() = formatSeconds(toDouble(DurationUnit.SECONDS))

  private fun toolchain(): Toolchain =
    Toolchain(
      ffmpeg = "/usr/bin/ffmpeg",
      ffprobe = "/usr/bin/ffprobe",
      version = FfmpegVersion("ffmpeg version 9.0.1", 9, 0),
      filters = emptySet(),
      encoders = emptySet(),
    )
}
