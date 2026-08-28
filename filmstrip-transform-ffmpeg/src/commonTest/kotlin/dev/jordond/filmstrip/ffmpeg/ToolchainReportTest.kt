package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.parseNameListing
import dev.jordond.filmstrip.ffmpeg.internal.parseVersion
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ToolchainReportTest {
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
    val version = parseVersion("ffmpeg version 7.1.4-0+deb13u1 Copyright (c) 2000-2025")

    version.major shouldBe 7
    version.isUsable shouldBe true
  }

  @Test
  fun `reads an n-prefixed tag`() {
    parseVersion("ffmpeg version n7.1-42-gabcdef012 Copyright").major shouldBe 7
  }

  @Test
  fun `refuses a version below the floor`() {
    val version = parseVersion("ffmpeg version 4.3.6 Copyright (c) 2000-2021")

    version.isUsable shouldBe false
    version.printed shouldBe "4.3.6"
  }

  @Test
  fun `accepts the floor itself`() {
    parseVersion("ffmpeg version 4.4 Copyright").isUsable shouldBe true
  }

  // A git build names no release. Refusing it would refuse every nightly, and the filter lists are
  // the real gate anyway.
  @Test
  fun `accepts a build with no release number`() {
    val version = parseVersion("ffmpeg version N-121284-g4c9e0eb0e6 Copyright")

    version.major shouldBe null
    version.isUsable shouldBe true
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
