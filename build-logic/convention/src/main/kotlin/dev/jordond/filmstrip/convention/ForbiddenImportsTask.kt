package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Guard A. Catches a forbidden `import` before it can become a dependency.
 */
@CacheableTask
abstract class ForbiddenImportsTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  /**
   * Import prefixes that must not appear, e.g. `androidx.compose`, `org.jetbrains.compose`.
   *
   * A rule matches the exact FQN or anything under it as a package. Kotlin/Native flattens every
   * ObjC class to `platform.<Framework>.<Class>`, so a rule may end in `*` to cover a class
   * family: `platform.AVFoundation.AVPlayer*` catches `AVPlayerItem` and `AVPlayerLayer` too.
   */
  @get:Input
  abstract val forbiddenPrefixes: SetProperty<String>

  /**
   * Exact FQNs the rules do not apply to, checked before [forbiddenPrefixes].
   */
  @get:Input
  abstract val allowedPrefixes: SetProperty<String>

  @get:Input
  abstract val moduleName: Property<String>

  @TaskAction
  fun check() {
    val prefixes = forbiddenPrefixes.get()
    val allowed = allowedPrefixes.get()
    val violations =
      sources.asFileTree
        .matching { include("**/*.kt") }
        .flatMap { file ->
          file.useLines { lines ->
            lines
              .withIndex()
              .filter { (_, line) -> line.startsWith("import ") }
              .filter { (_, line) ->
                val fqn = line.removePrefix("import ").substringBefore(' ').trim()
                if (fqn in allowed) return@filter false
                prefixes.any { rule ->
                  if (rule.endsWith("*")) {
                    fqn.startsWith(rule.dropLast(1))
                  } else {
                    fqn == rule || fqn.startsWith("$rule.")
                  }
                }
              }.map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
              .toList()
          }
        }

    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("Forbidden imports in ${moduleName.get()} (${violations.size}):")
          violations.forEach { appendLine("  $it") }
          appendLine()
          appendLine("See LAYERING in build-logic Layering.kt for the layering contract.")
        },
      )
    }
  }
}
