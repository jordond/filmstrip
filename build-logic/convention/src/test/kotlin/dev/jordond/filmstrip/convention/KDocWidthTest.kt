package dev.jordond.filmstrip.convention

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import kotlin.test.Test

/**
 * Checks which lines [kdocWidthViolations] flags, then runs `checkKDocWidth` on a stub module to read the file:line it
 * fails with.
 */
class KDocWidthTest {
  private fun kdocLine(width: Int): String = " * " + "x".repeat(width - 3)

  @Test
  fun `a KDoc line at 120 columns passes`() {
    kdocWidthViolations(listOf("/**", kdocLine(120), " */")).shouldBeEmpty()
  }

  @Test
  fun `a KDoc line at 121 columns fails`() {
    kdocWidthViolations(listOf("/**", kdocLine(121), " */")) shouldContainExactly listOf(2)
  }

  @Test
  fun `a code line at 130 columns is ignored`() {
    val code = "val x = \"" + "y".repeat(120) + "\""
    kdocWidthViolations(listOf("/**", " * Doc.", " */", code)).shouldBeEmpty()
  }

  @Test
  fun `a long line closing the KDoc fails and the code after it is ignored`() {
    val closing = kdocLine(121) + " */"
    val code = "fun f() = \"" + "y".repeat(120) + "\""
    kdocWidthViolations(listOf("/**", closing, code)) shouldContainExactly listOf(2)
  }

  @Test
  fun `a long single line KDoc fails`() {
    kdocWidthViolations(listOf("/** " + "x".repeat(120) + " */", "val a = 1")) shouldContainExactly listOf(1)
  }

  @Test
  fun `a block comment and a glob in a string are not KDoc`() {
    val long = "y".repeat(130)
    kdocWidthViolations(listOf("/* $long */", "val glob = \"src/**/$long\"")).shouldBeEmpty()
  }

  @Test
  fun `the task names the file and line of a long KDoc line`() {
    val project = Files.createTempDirectory("kdoc-width-project").toFile()
    project.resolve("settings.gradle").writeText("rootProject.name = 'stub'\n")
    project.resolve("build.gradle").writeText(
      """
      plugins {
        id 'base'
        id 'filmstrip.root' apply false
      }
      dev.jordond.filmstrip.convention.KDocWidthTaskKt.configureKDocWidthCheck(project)
      """.trimIndent(),
    )
    project.resolve("src/main/kotlin/Doc.kt").apply { parentFile.mkdirs() }.writeText(
      listOf("/**", kdocLine(121), " */", "val a = 1", "").joinToString("\n"),
    )
    project.resolve("src/main/kotlin/Fine.kt").writeText(
      listOf("/**", kdocLine(120), " */", "val b = 1", "").joinToString("\n"),
    )

    val output =
      GradleRunner
        .create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments("check")
        .buildAndFail()
        .output

    output shouldContain "Doc.kt:2"
    output shouldNotContain "Fine.kt"
    project.deleteRecursively()
  }
}
