package dev.jordond.filmstrip.convention.testmedia

import io.kotest.matchers.shouldBe
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Runs a real generate task, under the configuration cache the project builds with, against a stub ffmpeg on the PATH.
 * No specs are declared, so the stub only answers the probes that read the build.
 *
 * A JVM finds an executable on the PATH it started with, so the daemon keeps the directory it was first given. Every
 * test rewrites the stub in one directory, and the class has its own daemon, which started with that directory.
 */
class GenerateBuildInputTest {
  private val project = Files.createTempDirectory("generate-project").toFile()

  private val fixtureList = project.resolve("build/test-fixtures/fixtures.txt")

  @BeforeTest
  fun writeProject() {
    project.resolve("settings.gradle").writeText("rootProject.name = 'stub'\n")
    project.resolve("build.gradle").writeText(
      """
      plugins { id 'filmstrip.root' apply false }
      dev.jordond.filmstrip.convention.testmedia.TestMediaKt.testMedia(project, 'stub', [])
      """.trimIndent(),
    )
  }

  @Test
  fun `the same build stays up to date`() {
    stubFfmpeg(version = "9.0.2")

    generate() shouldBe TaskOutcome.SUCCESS
    generate() shouldBe TaskOutcome.UP_TO_DATE
  }

  @Test
  fun `a new ffmpeg version runs generate again and heads the list with it`() {
    stubFfmpeg(version = "9.0.2")
    generate() shouldBe TaskOutcome.SUCCESS

    stubFfmpeg(version = "9.0.3")

    generate() shouldBe TaskOutcome.SUCCESS
    TestMediaManifest.readBuild(fixtureList) shouldBe recordOf(version = "9.0.3")
  }

  @Test
  fun `a rebuilt encoder under the same ffmpeg version runs generate again`() {
    stubFfmpeg(version = "9.0.2", x265 = "4.1+1-aaaaaaa")
    generate() shouldBe TaskOutcome.SUCCESS

    stubFfmpeg(version = "9.0.2", x265 = "4.1+1-bbbbbbb")

    generate() shouldBe TaskOutcome.SUCCESS
    TestMediaManifest.readBuild(fixtureList) shouldBe recordOf(version = "9.0.2", x265 = "4.1+1-bbbbbbb")
  }

  @Test
  fun `another architecture runs generate again`() {
    stubFfmpeg(version = "9.0.2")
    generate() shouldBe TaskOutcome.SUCCESS

    generate("-Dos.arch=$OTHER_ARCHITECTURE") shouldBe TaskOutcome.SUCCESS

    TestMediaManifest.readBuild(fixtureList) shouldBe recordOf(version = "9.0.2", architecture = OTHER_ARCHITECTURE)
  }

  private fun stubFfmpeg(
    version: String,
    x265: String = "4.1",
  ) {
    stubs.resolve("ffmpeg").apply {
      writeText(
        """
        #!/bin/sh
        if [ "${'$'}1" = "-version" ]; then
          echo "ffmpeg version $version Copyright (c) the FFmpeg developers"
        else
          echo "x264 - core 165 - H.264 x265 (build 215) - $x265 - H.265"
        fi
        """.trimIndent(),
      )
      setExecutable(true)
    }
  }

  private fun recordOf(
    version: String,
    x265: String = "4.1",
    architecture: String = System.getProperty("os.arch"),
  ): String = "$version libx264 core 165 libx265 $x265 arch $architecture"

  private fun generate(vararg arguments: String): TaskOutcome? =
    GradleRunner
      .create()
      .withProjectDir(project)
      .withPluginClasspath()
      .withTestKitDir(testKit)
      .withEnvironment(mapOf("PATH" to "${stubs.path}${File.pathSeparator}${System.getenv("PATH")}"))
      .withArguments(listOf(TASK, "--configuration-cache") + arguments)
      .build()
      .task(TASK)
      ?.outcome

  private companion object {
    const val TASK = ":generateStubTestMedia"

    const val OTHER_ARCHITECTURE = "riscv64"

    val stubs: File = Files.createTempDirectory("generate-stubs").toFile()

    val testKit: File = Files.createTempDirectory("generate-testkit").toFile()
  }
}
