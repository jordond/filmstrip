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
 * Prints the project's path, without its leading colon, when `iosSimulatorArm64Test` has a class to run under the
 * iOS-only rule. That is a Kotlin file in [iosOnlyDirs] holding a `@Test`, or one in [sharedDirs] whose class is in
 * [IOS_CANARY_CLASSES].
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
   * The Kotlin directories of the source sets in [IOS_ONLY_SOURCE_SETS] the iOS simulator test compilation reads.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val iosOnlyDirs: ConfigurableFileCollection

  /**
   * The Kotlin directories of every other source set the iOS simulator test compilation reads.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sharedDirs: ConfigurableFileCollection

  @TaskAction
  fun print() {
    val tests = { dirs: ConfigurableFileCollection ->
      dirs.asFileTree
        .filter { it.extension == "kt" }
        .map { it to it.readText() }
        .filter { (_, text) -> TEST_ANNOTATION.containsMatchIn(text) }
    }
    val canary =
      tests(sharedDirs).any { (file, text) ->
        val pkg = PACKAGE.find(text)?.groupValues?.get(1)
        (if (pkg == null) file.nameWithoutExtension else "$pkg.${file.nameWithoutExtension}") in IOS_CANARY_CLASSES
      }
    if (canary || tests(iosOnlyDirs).isNotEmpty()) println(modulePath.get().removePrefix(":"))
  }

  private companion object {
    val TEST_ANNOTATION = Regex("""@Test\b""")
    val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
  }
}

/**
 * Registers `printIosTestModules` in this project, reading the source sets of its `iosSimulatorArm64` test
 * compilation. A project with no such target gets the task too, with nothing to read, so it prints nothing.
 */
internal fun Project.configureIosTestModules() {
  val module = this
  val sourceSets =
    module.provider {
      module.extensions
        .findByType(KotlinMultiplatformExtension::class.java)
        ?.targets
        ?.findByName(IOS_SIMULATOR_TARGET)
        ?.compilations
        ?.findByName("test")
        ?.allKotlinSourceSets
        .orEmpty()
    }
  tasks.register<PrintIosTestModulesTask>("printIosTestModules") {
    group = "help"
    description = "Prints this module's path when its iOS simulator tests run a class under the iOS-only rule."
    modulePath.set(module.path)

    fun dirs(iosOnly: Boolean) =
      sourceSets.map { sets ->
        sets.filter { (it.name in IOS_ONLY_SOURCE_SETS) == iosOnly }.flatMap { it.kotlin.srcDirs }
      }
    iosOnlyDirs.from(dirs(iosOnly = true))
    sharedDirs.from(dirs(iosOnly = false))
  }
}

private const val IOS_SIMULATOR_TARGET = "iosSimulatorArm64"
