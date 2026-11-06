package dev.jordond.filmstrip.convention.testmedia

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.GradleException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TestMediaManifestTest {
  private val directory = Files.createTempDirectory("manifest").toFile()

  @Test
  fun `a written manifest reads back sorted by name`() {
    val file = directory.resolve(TestMediaManifest.FILE_NAME)
    val entries =
      listOf(
        TestMediaEntry("b.mp4", "bb".repeat(32), 20),
        TestMediaEntry("a.heic", "aa".repeat(32), 10),
      )

    TestMediaManifest.write(file, entries, build = "8.0.1-3ubuntu2")

    TestMediaManifest.read(file) shouldBe entries.sortedBy { it.fileName }
    TestMediaManifest.readBuild(file) shouldBe "8.0.1-3ubuntu2"
  }

  @Test
  fun `a manifest with no build header reads as an unknown build`() {
    val file = directory.resolve(TestMediaManifest.FILE_NAME)
    file.writeText("# filmstrip test fixtures\n# name sha256 bytes\nclip.mp4 ${"aa".repeat(32)} 10\n")

    TestMediaManifest.readBuild(file) shouldBe null
    TestMediaManifest.read(file).single().fileName shouldBe "clip.mp4"
  }

  @Test
  fun `the build names ffmpeg from its version line`() {
    fun ffmpegIn(line: String) =
      TestMediaManifest.buildOf(line, X264_STREAM, X265_STREAM, ARCH).substringBefore(" libx264 ")

    ffmpegIn("ffmpeg version 8.0.1-3ubuntu2 Copyright (c) 2000-2025 the FFmpeg developers") shouldBe "8.0.1-3ubuntu2"
    ffmpegIn("ffmpeg version 9.0.2 Copyright (c) 2000-2026 the FFmpeg developers\n") shouldBe "9.0.2"
    ffmpegIn(" something else ") shouldBe "something else"
  }

  @Test
  fun `the build names both encoders from their streams`() {
    TestMediaManifest.buildOf(BANNER, X264_STREAM, X265_STREAM, ARCH) shouldBe
      "9.0.2 libx264 core 165 r3222 b35605a libx265 4.3+1-e9b8812:[Mac OS X][clang 21.0.0][64 bit] 10bit arch aarch64"
  }

  @Test
  fun `a libx264 with no revision in its sei is named by its core`() {
    val stream = "\u0000x264 - core 165 - H.264/MPEG-4 AVC codec - Copyleft 2003-2025 - options: threads=1"

    TestMediaManifest.buildOf(BANNER, stream, X265_STREAM, ARCH) shouldContain "libx264 core 165 libx265"
  }

  @Test
  fun `a stream with no encoder sei fails instead of reading as no encoder`() {
    assertFailsWith<GradleException> {
      TestMediaManifest.buildOf(BANNER, "no sei", X265_STREAM, ARCH)
    }.message shouldContain "x264"
    assertFailsWith<GradleException> {
      TestMediaManifest.buildOf(BANNER, X264_STREAM, "no sei", ARCH)
    }.message shouldContain "x265"
  }

  @Test
  fun `the whole build reads back from a manifest`() {
    val build = TestMediaManifest.buildOf(BANNER, X264_STREAM, X265_STREAM, ARCH)
    val file = directory.resolve(TestMediaManifest.FILE_NAME)

    TestMediaManifest.write(file, listOf(TestMediaEntry("clip.mp4", "aa".repeat(32), 10)), build)

    TestMediaManifest.readBuild(file) shouldBe build
  }

  @Test
  fun `a row without three fields names that row`() {
    val file = directory.resolve(TestMediaManifest.FILE_NAME)
    file.writeText("# header\nclip.mp4 ${"aa".repeat(32)} 10\nbroken.mp4 deadbeef\n")

    val error = assertFailsWith<GradleException> { TestMediaManifest.read(file) }

    error.message shouldContain "broken.mp4 deadbeef"
  }

  @Test
  fun `an object is keyed by its digest and its extension`() {
    val entry = TestMediaEntry("overlay.heic", "ab".repeat(32), 488)

    TestMediaManifest.keyFor(entry) shouldBe "test-fixtures/${"ab".repeat(32)}.heic"
    TestMediaManifest.urlFor(entry) shouldBe "${TestMediaManifest.HOST}/test-fixtures/${"ab".repeat(32)}.heic"
  }

  private companion object {
    const val ARCH = "aarch64"
    const val BANNER = "ffmpeg version 9.0.2 Copyright (c) 2000-2026 the FFmpeg developers"
    const val X264_STREAM = "\u0001x264 - core 165 r3222 b35605a - H.264/MPEG-4 AVC codec - Copyleft 2003-2025"
    const val X265_STREAM =
      "\u0001x265 (build 217) - 4.3+1-e9b8812:[Mac OS X][clang 21.0.0][64 bit] 10bit - H.265/HEVC codec - Copyright"
  }
}
