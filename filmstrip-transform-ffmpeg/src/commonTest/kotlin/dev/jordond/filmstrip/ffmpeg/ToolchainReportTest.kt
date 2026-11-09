package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.process.parseBitstreamFilters
import dev.jordond.filmstrip.ffmpeg.internal.process.parseInputProtocols
import dev.jordond.filmstrip.ffmpeg.internal.process.parseNameListing
import dev.jordond.filmstrip.ffmpeg.internal.process.parseVersion
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ToolchainReportTest {
  @Test
  fun `reads the input protocols and leaves the output ones out`() {
    val listing = "Supported file protocols:\nInput:\n  file\n  tcp\nOutput:\n  file\n  rtmp\n"

    parseInputProtocols(listing) shouldBe setOf("file", "tcp")
    parseInputProtocols("Supported file protocols:\nInput:\n  file\nOutput:\n  tcp\n") shouldBe setOf("file")
    parseInputProtocols("") shouldBe emptySet()
  }

  @Test
  fun `reads a release version`() {
    val version = parseVersion("ffmpeg version 9.0.1 Copyright (c) 2000-2026 the FFmpeg developers")

    version.major shouldBe 9
    version.minor shouldBe 0
    version.printed shouldBe "9.0.1"
    version.meetsFloor() shouldBe true
  }

  @Test
  fun `reads a distribution version`() {
    val version = parseVersion("ffmpeg version 8.0.1-3ubuntu2 Copyright (c) 2000-2025")

    version.major shouldBe 8
    version.minor shouldBe 0
    version.printed shouldBe "8.0.1-3ubuntu2"
    version.meetsFloor() shouldBe true
  }

  @Test
  fun `reads an n-prefixed tag`() {
    val version = parseVersion("ffmpeg version n8.0.1-42-gabcdef012 Copyright")

    version.major shouldBe 8
    version.minor shouldBe 0
    version.meetsFloor() shouldBe true
  }

  @Test
  fun `refuses a version below the floor`() {
    for (banner in listOf("4.4.5", "6.1.1-3ubuntu5", "7.1", "n7.1.2", "7.1.4-0+deb13u1")) {
      val version = parseVersion("ffmpeg version $banner Copyright (c) 2000-2025")

      withClue(banner) {
        version.meetsFloor() shouldBe false
        version.printed shouldBe banner
      }
    }
  }

  @Test
  fun `accepts the floor and every release after it`() {
    for (banner in listOf("8.0", "8.0.1", "8.1", "9.0.2", "10.0")) {
      withClue(banner) { parseVersion("ffmpeg version $banner Copyright").meetsFloor() shouldBe true }
    }
  }

  // A git build names no release, so its libavformat version is held against the one the floor ships. Master reached
  // major 62 ahead of the 8.0 branch, so a 62 below the floor's minor is refused.
  @Test
  fun `holds a build with no release number to its libavformat version`() {
    val cases =
      listOf(
        Triple(61, 3, false),
        Triple(62, 2, false),
        Triple(62, 3, true),
        Triple(62, 4, true),
        Triple(63, 1, true),
      )
    for (banner in listOf("N-121284-g4c9e0eb0e6", "2025-09-01-git-1d33e7c3a5")) {
      for ((libavformat, libavformatMinor, meets) in cases) {
        val version = parseVersion(versionOutput(banner, libavformat, libavformatMinor))

        withClue("$banner on libavformat $libavformat.$libavformatMinor") {
          version.major shouldBe null
          version.libavformatMajor shouldBe libavformat
          version.libavformatMinor shouldBe libavformatMinor
          version.readable shouldBe true
          version.meetsFloor() shouldBe meets
        }
      }
    }
  }

  // The libavformat lines 8.0.1 and 9.0.2 print, under a banner that names no release.
  @Test
  fun `accepts the libavformat versions 8_0_1 and 9_0_2 print`() {
    val floor = parseVersion(versionOutput("N-121284-g4c9e0eb0e6", 62, 3))
    val nine = parseVersion(versionOutput("N-121284-g4c9e0eb0e6", 63, 1, micro = 102))

    floor.libavformatMinor shouldBe 3
    floor.meetsFloor() shouldBe true
    nine.libavformatMajor shouldBe 63
    nine.libavformatMinor shouldBe 1
    nine.meetsFloor() shouldBe true
  }

  @Test
  fun `refuses a build that prints neither a release nor a libavformat version`() {
    for (banner in listOf("N-121284-g4c9e0eb0e6", "2025-09-01-git-1d33e7c3a5")) {
      val version = parseVersion("ffmpeg version $banner Copyright\nbuilt with gcc 15\n")

      withClue(banner) {
        version.readable shouldBe false
        version.meetsFloor() shouldBe false
      }
    }
  }

  @Test
  fun `holds a release to its own number whatever libavformat it prints`() {
    parseVersion(versionOutput("7.1.2", 62)).meetsFloor() shouldBe false
    parseVersion(versionOutput("8.0.1", 61)).meetsFloor() shouldBe true
    parseVersion(versionOutput("9.0.2", 63)).meetsFloor() shouldBe true
  }

  // What `ffmpeg -version` prints for [banner], with the libavformat line at [libavformat].[minor].[micro] among the
  // libraries.
  private fun versionOutput(
    banner: String,
    libavformat: Int,
    minor: Int = 3,
    micro: Int = 100,
  ): String {
    val library = "$libavformat.${minor.toString().padStart(3)}.$micro"
    return "ffmpeg version $banner Copyright (c) 2000-2025 the FFmpeg developers\n" +
      "configuration: --enable-gpl\n" +
      "libavutil      60.  8.100 / 60.  8.100\n" +
      "libavcodec     62. 11.100 / 62. 11.100\n" +
      "libavformat    $library / $library\n"
  }

  @Test
  fun `reads a filter listing`() {
    val listing =
      """
      Filters:
        T.. = Timeline support
        .S. = Slice threading
        ..C = Command support
        A = Audio input/output
       ... abench            A->A       Benchmark part of a filtergraph.
       T.C overlay           VV->V      Overlay a video source on top of the input.
       ... concat            N->N       Concatenate audio and video streams.
      """.trimIndent()

    val filters = parseNameListing(listing)

    filters.contains("overlay") shouldBe true
    filters.contains("concat") shouldBe true
    filters.contains("drawtext") shouldBe false
    filters.contains("=") shouldBe false
  }

  @Test
  fun `reads an encoder listing`() {
    val listing =
      """
      Encoders:
       V..... = Video
       A..... = Audio
       ------
       V....D libx264             libx264 H.264 / AVC / MPEG-4 AVC
       A....D aac                 AAC (Advanced Audio Coding)
      """.trimIndent()

    val encoders = parseNameListing(listing)

    encoders shouldBe setOf("libx264", "aac")
  }

  @Test
  fun `reads a bitstream filter listing`() {
    val listing = "Bitstream filters:\naac_adtstoasc\nnoise\nnull\n"

    parseBitstreamFilters(listing) shouldBe setOf("aac_adtstoasc", "noise", "null")
    parseBitstreamFilters("") shouldBe emptySet()
  }
}
