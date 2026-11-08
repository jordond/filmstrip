package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Prints this project's part of the module graph, one fact per line, in the shape [moduleGraphLines] gives.
 *
 * Run from the root as `./gradlew -q printModuleGraph`, every project's task runs, so the output is the whole build's
 * graph. `ci.sh --plan` reads it to work out which suites a change owes.
 */
abstract class PrintModuleGraphTask : DefaultTask() {
  /**
   * The lines this task prints.
   */
  @get:Input
  abstract val lines: ListProperty<String>

  @TaskAction
  fun print() {
    lines.get().forEach(::println)
  }
}

/**
 * Registers `printModuleGraph` in this project. The lines are read once the build scripts are done, so every
 * dependency and source set they declare is in them.
 */
internal fun Project.configureModuleGraph() {
  val module = this
  tasks.register<PrintModuleGraphTask>("printModuleGraph") {
    group = "help"
    description = "Prints this project's directory, project dependencies and the targets each source set feeds."
    lines.set(module.provider { module.moduleGraphLines() })
  }
}

/**
 * This project's module graph lines.
 *
 * - `project <path> <dir>` names the project and its directory relative to the root, `.` for the root itself.
 * - `dep <path> main|test <path>` is a project dependency, `test` when a test configuration declares it.
 * - `sourceSet <path> <name> <target>/<compilation>,... <dir>,...` lists every compilation a Kotlin source set
 * compiles into and its Kotlin directories relative to the root. An empty list prints as `-`.
 */
internal fun Project.moduleGraphLines(): List<String> {
  val directory = projectDir.relativeTo(rootDir).path.ifEmpty { "." }
  val dependencies =
    configurations
      .filter { configuration -> DECLARING_SUFFIXES.any { configuration.name.endsWith(it, ignoreCase = true) } }
      .flatMap { configuration ->
        val kind = if (configuration.isTestConfiguration()) "test" else "main"
        configuration.dependencies.withType(ProjectDependency::class.java).map { "dep $path $kind ${it.path}" }
      }.filterNot { it.endsWith(" $path") }
      .distinct()
      .sorted()
  val kotlin = extensions.findByType(KotlinMultiplatformExtension::class.java)
  val compilations =
    kotlin
      ?.targets
      ?.flatMap { target ->
        target.compilations.flatMap { compilation ->
          compilation.allKotlinSourceSets.map { it.name to "${target.name}/${compilation.name}" }
        }
      }.orEmpty()
      .groupBy({ it.first }, { it.second })
  val sourceSets =
    kotlin
      ?.sourceSets
      ?.sortedBy { it.name }
      ?.map { sourceSet ->
        val feeds =
          compilations[sourceSet.name]
            .orEmpty()
            .distinct()
            .sorted()
            .joinToString(",")
            .ifEmpty { "-" }
        val dirs =
          sourceSet.kotlin.srcDirs
            .map { it.relativeTo(rootDir).path }
            .sorted()
            .joinToString(",")
        "sourceSet $path ${sourceSet.name} $feeds ${dirs.ifEmpty { "-" }}"
      }.orEmpty()
  return listOf("project $path $directory") + dependencies + sourceSets
}

private fun Configuration.isTestConfiguration(): Boolean = name.contains("Test") || name.startsWith("test")

private val DECLARING_SUFFIXES = listOf("api", "implementation", "compileOnly", "runtimeOnly")
