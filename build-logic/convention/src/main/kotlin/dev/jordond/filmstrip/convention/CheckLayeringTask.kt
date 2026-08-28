package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Guard B. Catches what Guard A cannot see: a forbidden coordinate arriving transitively.
 */
abstract class CheckLayeringTask : DefaultTask() {
  @get:Input
  abstract val moduleName: Property<String>

  /**
   * Project paths this module may depend on, transitively closed.
   */
  @get:Input
  abstract val allowedProjects: SetProperty<String>

  /**
   * `group` or `group:artifact` prefixes that must not appear on any resolved classpath.
   */
  @get:Input
  abstract val forbiddenExternals: SetProperty<String>

  /**
   * Flattened `":path"` / `"group:artifact"` ids, captured from resolution results.
   */
  @get:Input
  abstract val resolvedIds: SetProperty<String>

  /**
   * Names of the configurations actually inspected, used to detect a silent no-op.
   */
  @get:Input
  abstract val inspectedConfigurations: SetProperty<String>

  @TaskAction
  fun check() {
    val self = moduleName.get()

    if (inspectedConfigurations.get().isEmpty()) {
      throw GradleException(
        "checkLayering for $self matched ZERO resolvable configurations. The suffix list " +
          "in Layering.kt is stale. Run `./gradlew $self:resolvableConfigurations` and " +
          "update CHECKED_SUFFIXES.",
      )
    }

    val allowed = allowedProjects.get()
    val forbidden = forbiddenExternals.get()

    val violations =
      resolvedIds.get().sorted().mapNotNull { id ->
        when {
          id.startsWith(":") && id != self && id !in allowed -> {
            "illegal project dependency: $id"
          }
          !id.startsWith(":") -> {
            forbidden
              .firstOrNull { id == it || id.startsWith("$it:") || id.startsWith("$it.") }
              ?.let { rule -> "forbidden coordinate: $id (rule '$rule')" }
          }
          else -> {
            null
          }
        }
      }

    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("Layering violations in $self (${violations.size}):")
          violations.forEach { appendLine("  - $it") }
          appendLine()
          appendLine("Inspected: ${inspectedConfigurations.get().sorted().joinToString()}")
          appendLine("See LAYERING in build-logic Layering.kt for the contract.")
        },
      )
    }
  }
}
