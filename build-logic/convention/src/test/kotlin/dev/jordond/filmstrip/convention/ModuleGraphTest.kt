package dev.jordond.filmstrip.convention

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import kotlin.test.Test

/**
 * Builds a stub with a jvm and js `base` module and a jvm only `app` module that uses `base` and tests with `fixtures`,
 * then reads what `printModuleGraph` prints from the root.
 */
class ModuleGraphTest {
  private val project = Files.createTempDirectory("module-graph-project").toFile()

  @Test
  fun `the graph names each project its dependencies and where each source set compiles`() {
    project.resolve("settings.gradle").writeText("rootProject.name = 'stub'\ninclude ':base', ':app', ':fixtures'\n")
    project.resolve("build.gradle").writeText(
      """
      plugins {
        id 'org.jetbrains.kotlin.multiplatform' apply false
        id 'filmstrip.root' apply false
      }
      allprojects { dev.jordond.filmstrip.convention.ModuleGraphKt.configureModuleGraph(project) }
      """.trimIndent(),
    )
    writeModule("base", "jvm()\n  js { browser() }")
    writeModule("fixtures", "jvm()")
    writeModule(
      "app",
      """
      jvm()
        sourceSets {
          commonMain.dependencies { implementation(project(':base')) }
          commonTest.dependencies { implementation(project(':fixtures')) }
        }
      """.trimIndent(),
    )

    val printed =
      GradleRunner
        .create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments("-q", "printModuleGraph", "--configuration-cache")
        .build()
        .output
        .lines()

    printed shouldContainAll
      listOf(
        "project : .",
        "project :app app",
        "dep :app main :base",
        "dep :app test :fixtures",
        "sourceSet :app commonTest jvm/test app/src/commonTest/kotlin",
        "sourceSet :base jsTest js/test base/src/jsTest/kotlin",
      )
    val baseMain = printed.single { it.startsWith("sourceSet :base commonMain ") }.split(" ")
    baseMain[3].split(",") shouldContainAll listOf("js/main", "jvm/main")
    baseMain[4] shouldBe "base/src/commonMain/kotlin"
    printed shouldNotContain "dep :base main :app"
  }

  private fun writeModule(
    name: String,
    targets: String,
  ) {
    project.resolve(name).mkdirs()
    project.resolve("$name/build.gradle").writeText(
      """
      plugins { id 'org.jetbrains.kotlin.multiplatform' }
      repositories { mavenCentral() }
      kotlin {
        $targets
      }
      """.trimIndent(),
    )
  }
}
