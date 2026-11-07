package dev.jordond.filmstrip.ffmpeg

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
    version.isUsable shouldBe true
  }

  @Test
  fun `reads a distribution version`() {
    val version = parseVersion("ffmpeg version 8.0.1-3ubuntu2 Copyright (c) 2000-2025")

    version.major shouldBe 8
    version.minor shouldBe 0
    version.printed shouldBe "8.0.1-3ubuntu2"
    version.isUsable shouldBe true
  }

  @Test
  fun `reads an n-prefixed tag`() {
    val version = parseVersion("ffmpeg version n8.0.1-42-gabcdef012 Copyright")

    version.major shouldBe 8
    version.minor shouldBe 0
    version.isUsable shouldBe true
  }

  @Test
  fun `refuses a version below the floor`() {
    for (banner in listOf("4.4.5", "6.1.1-3ubuntu5", "7.1", "n7.1.2", "7.1.4-0+deb13u1")) {
      val version = parseVersion("ffmpeg version $banner Copyright (c) 2000-2025")

      withClue(banner) {
        version.isUsable shouldBe false
        version.printed shouldBe banner
      }
    }
  }

  @Test
  fun `accepts the floor and every release after it`() {
    for (banner in listOf("8.0", "8.0.1", "8.1", "9.0.2", "10.0")) {
      withClue(banner) { parseVersion("ffmpeg version $banner Copyright").isUsable shouldBe true }
    }
  }

  // A git build names no release, so there is nothing to hold against the floor.
  @Test
  fun `accepts a build with no release number`() {
    for (banner in listOf("N-121284-g4c9e0eb0e6", "2025-09-01-git-1d33e7c3a5")) {
      val version = parseVersion("ffmpeg version $banner Copyright")

      withClue(banner) {
        version.major shouldBe null
        version.isUsable shouldBe true
      }
    }
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
}
