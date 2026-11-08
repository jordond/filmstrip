package dev.jordond.filmstrip.convention

import io.kotest.matchers.collections.shouldContainExactly
import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import kotlin.test.Test

/**
 * Builds a stub with three modules, one with an iOS simulator target and a test, one with the target and no test and
 * one with only jvm, then reads what `printIosTestModules` prints from the root.
 */
class IosTestModulesTest {
  private val project = Files.createTempDirectory("ios-modules-project").toFile()

  @Test
  fun `only a module whose iOS simulator tests hold a test is printed`() {
    project.resolve("settings.gradle").writeText("rootProject.name = 'stub'\ninclude ':tested', ':untested', ':jvm'\n")
    project.resolve("build.gradle").writeText(
      """
      plugins {
        id 'org.jetbrains.kotlin.multiplatform' apply false
        id 'filmstrip.root' apply false
      }
      allprojects { dev.jordond.filmstrip.convention.IosTestModulesKt.configureIosTestModules(project) }
      """.trimIndent(),
    )
    writeModule("tested", "iosSimulatorArm64()")
    writeModule("untested", "iosSimulatorArm64()")
    writeModule("jvm", "jvm()")
    project.resolve("tested/src/commonTest/kotlin/StubTest.kt").apply { parentFile.mkdirs() }.writeText(
      "import kotlin.test.Test\n\nclass StubTest {\n  @Test fun runs() {}\n}\n",
    )
    project.resolve("untested/src/commonTest/kotlin/Fixture.kt").apply { parentFile.mkdirs() }.writeText(
      "val fixture = 1\n",
    )

    val printed =
      GradleRunner
        .create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments("-q", "printIosTestModules", "--configuration-cache")
        .build()
        .output
        .lines()
        .filter { it.isNotBlank() && !it.startsWith("w:") }

    printed shouldContainExactly listOf("tested")
  }

  private fun writeModule(
    name: String,
    target: String,
  ) {
    project.resolve(name).mkdirs()
    project.resolve("$name/build.gradle").writeText(
      """
      plugins { id 'org.jetbrains.kotlin.multiplatform' }
      repositories { mavenCentral() }
      kotlin { $target }
      """.trimIndent(),
    )
  }
}
