package dev.jordond.filmstrip.convention.testmedia

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PublishTestMediaTest {
  private val directory = Files.createTempDirectory("publish").toFile()

  private val specs = listOf("first", "second", "third").map(::spec)

  private val pinnedEntries = specs.map { directory.writeFixture(it) }

  @Test
  fun `a changed fixture on the pinned build is checked as before and re-pinned`() {
    val pinned = directory.pin(pinnedEntries, PINNED)
    val changed = changeSecond()

    val entries = publish(pinned, directory.generatedList(changed, PINNED))

    entries.map { it.sha256 } shouldContainExactly changed.map { it.sha256 }
    TestMediaManifest.read(pinned) shouldBe changed
  }

  @Test
  fun `a changed fixture on the pinned build that nothing verified still fails`() {
    val pinned = directory.pin(pinnedEntries, PINNED)
    val generated = directory.generatedList(pinnedEntries, PINNED)
    changeSecond()

    val error = assertFailsWith<GradleException> { publish(pinned, generated) }

    error.message shouldContain "second.mp4 (sha256"
  }

  @Test
  fun `a changed fixture on another build fails naming both builds and the file`() {
    val pinned = directory.pin(pinnedEntries, PINNED)
    val generated = directory.generatedList(changeSecond(), OTHER)

    val error = assertFailsWith<GradleException> { publish(pinned, generated) }

    error.message shouldContain "ffmpeg $OTHER"
    error.message shouldContain "ffmpeg $PINNED"
    error.message shouldContain "second.mp4"
    error.message shouldNotContain "first.mp4"
    TestMediaManifest.read(pinned) shouldBe pinnedEntries
  }

  @Test
  fun `repinning lets a changed fixture on another build through`() {
    val pinned = directory.pin(pinnedEntries, PINNED)
    val changed = changeSecond()

    publish(pinned, directory.generatedList(changed, OTHER), repin = true)

    TestMediaManifest.read(pinned) shouldBe changed
    TestMediaManifest.readBuild(pinned) shouldBe OTHER
  }

  @Test
  fun `another build with unchanged fixtures passes`() {
    val pinned = directory.pin(pinnedEntries, PINNED)

    publish(pinned, directory.generatedList(pinnedEntries, OTHER))

    TestMediaManifest.readBuild(pinned) shouldBe OTHER
  }

  @Test
  fun `a manifest with no build header counts as another build`() {
    val pinned = directory.pin(pinnedEntries, build = null)
    val generated = directory.generatedList(changeSecond(), PINNED)

    val error = assertFailsWith<GradleException> { publish(pinned, generated) }

    error.message shouldContain "unknown build"
  }

  @Test
  fun `a failed upload leaves the pinned manifest as it was`() {
    val pinned = directory.pin(pinnedEntries, PINNED)
    val before = pinned.readText()
    val generated = directory.generatedList(changeSecond(), PINNED)
    val uploaded = mutableListOf<String>()

    assertFailsWith<IllegalStateException> {
      publish(pinned, generated) { entry, _ ->
        check(entry.fileName != "second.mp4") { "upload failed" }
        uploaded += entry.fileName
      }
    }

    uploaded shouldContainExactly listOf("first.mp4")
    pinned.readText() shouldBe before
  }

  @Test
  fun `every upload runs before the manifest is written`() {
    val pinned = directory.pin(pinnedEntries, PINNED)
    val generated = directory.generatedList(changeSecond(), PINNED)
    val pinsSeen = mutableListOf<List<TestMediaEntry>>()

    publish(pinned, generated) { _, _ -> pinsSeen += TestMediaManifest.read(pinned) }

    pinsSeen shouldBe List(3) { pinnedEntries }
  }

  @Test
  fun `the same ffmpeg with another libx264 counts as another build`() {
    val pinnedBuild = TestMediaManifest.buildOf(BANNER, x264Stream("core 165 r3222 b35605a"), X265_STREAM, ARCH)
    val otherBuild = TestMediaManifest.buildOf(BANNER, x264Stream("core 165 r3223 c0ffee1"), X265_STREAM, ARCH)
    val pinned = directory.pin(pinnedEntries, pinnedBuild)
    val generated = directory.generatedList(changeSecond(), otherBuild)

    val error = assertFailsWith<GradleException> { publish(pinned, generated) }

    error.message shouldContain otherBuild
    error.message shouldContain pinnedBuild
    error.message shouldContain "second.mp4"
    TestMediaManifest.read(pinned) shouldBe pinnedEntries
  }

  @Test
  fun `the same ffmpeg with another libx265 counts as another build`() {
    val pinnedBuild = TestMediaManifest.buildOf(BANNER, x264Stream("core 165"), X265_STREAM, ARCH)
    val otherBuild =
      TestMediaManifest.buildOf(BANNER, x264Stream("core 165"), X265_STREAM.replace("4.1", "4.2"), ARCH)
    val pinned = directory.pin(pinnedEntries, pinnedBuild)
    val generated = directory.generatedList(changeSecond(), otherBuild)

    val error = assertFailsWith<GradleException> { publish(pinned, generated) }

    error.message shouldContain otherBuild
    error.message shouldContain pinnedBuild
    TestMediaManifest.read(pinned) shouldBe pinnedEntries
  }

  @Test
  fun `the same versions on another architecture count as another build`() {
    val pinnedBuild = TestMediaManifest.buildOf(BANNER, x264Stream("core 165"), X265_STREAM, "aarch64")
    val otherBuild = TestMediaManifest.buildOf(BANNER, x264Stream("core 165"), X265_STREAM, "x86_64")
    val pinned = directory.pin(pinnedEntries, pinnedBuild)
    val generated = directory.generatedList(changeSecond(), otherBuild)

    val error = assertFailsWith<GradleException> { publish(pinned, generated) }

    error.message shouldContain otherBuild
    error.message shouldContain pinnedBuild
    error.message shouldContain "second.mp4"
    TestMediaManifest.read(pinned) shouldBe pinnedEntries
  }

  @Test
  fun `a libx264 sei with no revision cannot tell two rebuilds on one core apart`() {
    val pinnedBuild = TestMediaManifest.buildOf(BANNER, x264Stream("core 165"), X265_STREAM, ARCH)
    val rebuilt = TestMediaManifest.buildOf(BANNER, x264Stream("core 165"), X265_STREAM, ARCH)
    val pinned = directory.pin(pinnedEntries, pinnedBuild)
    val changed = changeSecond()

    publish(pinned, directory.generatedList(changed, rebuilt))

    rebuilt shouldBe pinnedBuild
    TestMediaManifest.read(pinned) shouldBe changed
  }

  @Test
  fun `no upload writes the manifest and uploads nothing`() {
    val pinned = directory.pin(pinnedEntries, PINNED)
    val changed = changeSecond()

    val entries = publishFixtures(specs, directory, pinned, directory.generatedList(changed, PINNED), false, null)

    entries shouldBe changed
    TestMediaManifest.read(pinned) shouldBe changed
    TestMediaManifest.readBuild(pinned) shouldBe PINNED
  }

  @Test
  fun `the wrangler check does nothing under no upload`() {
    val project = project()
    project.publishTask().noUpload.set(true)
    val check = project.wranglerCheck()
    check.searchPath.set("/nonexistent")

    check.check()
  }

  @Test
  fun `the wrangler check names wrangler on a real upload`() {
    val check = project().wranglerCheck()
    check.searchPath.set("/nonexistent")

    val error = assertFailsWith<GradleException> { check.check() }

    error.message shouldContain "wrangler"
  }

  @Test
  fun `publish depends on the wrangler check and generate runs after it`() {
    val project = project()
    val publish = project.publishTask()
    val generate = project.tasks.getByName("generateClipsTestMedia")
    val check = project.wranglerCheck()

    publish.taskDependencies.getDependencies(publish) shouldContain check
    generate.mustRunAfter.getDependencies(generate) shouldContain check
  }

  @Test
  fun `generating alone does not ask for the wrangler check`() {
    val project = project()
    val generate = project.tasks.getByName("generateClipsTestMedia")

    generate.taskDependencies.getDependencies(generate) shouldNotContain project.wranglerCheck()
  }

  @Test
  fun `publish declares no outputs so a no upload run leaves nothing up to date`() {
    project().publishTask().outputs.hasOutput shouldBe false
  }

  @Test
  fun `no wrangler on the path is named`() {
    val bin = Files.createTempDirectory("bin").toFile()

    val error = assertFailsWith<GradleException> { requireOnPath("wrangler", bin.absolutePath) }

    error.message shouldContain "wrangler"
  }

  @Test
  fun `wrangler on the path passes`() {
    val bin = Files.createTempDirectory("bin").toFile()
    bin.resolve("wrangler").apply {
      writeText("#!/bin/sh\n")
      setExecutable(true)
    }

    requireOnPath("wrangler", "/nonexistent${File.pathSeparator}${bin.absolutePath}")
  }

  private fun changeSecond(): List<TestMediaEntry> =
    pinnedEntries.toMutableList().also { it[1] = directory.writeFixture(specs[1], "re-encoded") }

  private fun publish(
    pinned: File,
    generated: File,
    repin: Boolean = false,
    upload: (TestMediaEntry, File) -> Unit = { _, _ -> },
  ): List<TestMediaEntry> = publishFixtures(specs, directory, pinned, generated, repin, upload)

  private fun project(): Project =
    ProjectBuilder
      .builder()
      .withProjectDir(Files.createTempDirectory("wiring").toFile())
      .build()
      .also { it.testMedia("clips", specs) }

  private fun Project.publishTask(): PublishTestMediaTask =
    tasks.getByName("publishClipsTestMedia") as PublishTestMediaTask

  private fun Project.wranglerCheck(): CheckWranglerTask = tasks.getByName("checkClipsWrangler") as CheckWranglerTask

  private fun x264Stream(identity: String) = "x264 - $identity - H.264/MPEG-4 AVC codec"

  private companion object {
    const val ARCH = "aarch64"
    const val PINNED = "8.0.1-3ubuntu2"
    const val OTHER = "9.0.2"
    const val BANNER = "ffmpeg version 8.0.1-3ubuntu2 Copyright (c) 2000-2025 the FFmpeg developers"
    const val X265_STREAM = "x265 (build 215) - 4.1+1-1d117be:[Linux][GCC 15.2.0][64 bit] 10bit - H.265/HEVC codec"
  }
}
