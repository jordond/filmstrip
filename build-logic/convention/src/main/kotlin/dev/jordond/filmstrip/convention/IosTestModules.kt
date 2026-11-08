package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Prints the project's path, without its leading colon, when a Kotlin file in [sourceDirs] holds a `@Test`.
 *
 * Run from the root as `./gradlew -q printIosTestModules`, every project's task runs, so the output lists each module
 * whose `iosSimulatorArm64Test` task has tests to run. The CI matrix guard compares that list with the ios matrix.
 */
abstract class PrintIosTestModulesTask : DefaultTask() {
  /**
   * The project path this task prints.
   */
  @get:Input
  abstract val modulePath: Property<String>

  /**
   * The Kotlin directories of every source set the iOS simulator test compilation reads.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceDirs: ConfigurableFileCollection

  @TaskAction
  fun print() {
    val hasTests =
      sourceDirs.asFileTree.any { it.extension == "kt" && TEST_ANNOTATION.containsMatchIn(it.readText()) }
    if (hasTests) println(modulePath.get().removePrefix(":"))
  }

  private companion object {
    val TEST_ANNOTATION = Regex("""@Test\b""")
  }
}

/**
 * Registers `printIosTestModules` in this project, reading the source sets of its `iosSimulatorArm64` test
 * compilation. A project with no such target gets the task too, with nothing to read, so it prints nothing.
 */
internal fun Project.configureIosTestModules() {
  val module = this
  tasks.register<PrintIosTestModulesTask>("printIosTestModules") {
    group = "help"
    description = "Prints this module's path when its iOS simulator test compilation has tests."
    modulePath.set(module.path)
    sourceDirs.from(
      module.provider {
        module.extensions
          .findByType(KotlinMultiplatformExtension::class.java)
          ?.targets
          ?.findByName(IOS_SIMULATOR_TARGET)
          ?.compilations
          ?.findByName("test")
          ?.allKotlinSourceSets
          ?.flatMap { it.kotlin.srcDirs }
          .orEmpty()
      },
    )
  }
}

private const val IOS_SIMULATOR_TARGET = "iosSimulatorArm64"
