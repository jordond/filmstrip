package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Guard B. Catches what Guard A cannot see: a project or a coordinate arriving transitively.
 *
 * Every project on a resolved classpath is looked up in the layer map and checked against the layers this module may
 * reach. Test classpaths may also reach the fixture layer, main ones never can.
 */
abstract class CheckLayeringTask : DefaultTask() {
  @get:Input
  abstract val moduleName: Property<String>

  /**
   * The layer this module sits on, by name.
   */
  @get:Input
  abstract val layer: Property<String>

  /**
   * The layer of every layered project, keyed by project path.
   */
  @get:Input
  abstract val layers: MapProperty<String, String>

  /**
   * Layer names reachable from a main configuration.
   */
  @get:Input
  abstract val allowedLayers: SetProperty<String>

  /**
   * Layer names reachable from a configuration whose name contains `Test`.
   */
  @get:Input
  abstract val allowedTestLayers: SetProperty<String>

  /**
   * Flattened `":path"` / `"group:artifact"` ids from the main configurations.
   */
  @get:Input
  abstract val mainIds: SetProperty<String>

  /**
   * Flattened ids from the test configurations.
   */
  @get:Input
  abstract val testIds: SetProperty<String>

  /**
   * `group` or `group:artifact` prefixes that must not appear on any resolved classpath.
   */
  @get:Input
  abstract val forbiddenExternals: SetProperty<String>

  /**
   * Coordinates the rules do not apply to, checked before [forbiddenExternals].
   *
   * A rule matches the coordinate itself and the per-target artifacts a multiplatform publication splits it into, so
   * `group:artifact` covers `group:artifact-jvm` and `group:artifact-iosarm64`.
   */
  @get:Input
  abstract val allowedExternals: SetProperty<String>

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

    val violations =
      (violationsIn(mainIds.get(), allowedLayers.get()) + violationsIn(testIds.get(), allowedTestLayers.get()))
        .distinct()
        .sorted()

    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("Layering violations in $self (${violations.size}):")
          violations.forEach { appendLine("  - $it") }
          appendLine()
          appendLine("Inspected: ${inspectedConfigurations.get().sorted().joinToString()}")
          appendLine("See LAYERS in build-logic Layering.kt for the contract.")
        },
      )
    }
  }

  private fun violationsIn(
    ids: Set<String>,
    allowed: Set<String>,
  ): List<String> {
    val self = moduleName.get()
    val mine = layer.get()
    val known = layers.get()
    val forbidden = forbiddenExternals.get()
    val allowedCoordinates = allowedExternals.get()

    return ids.mapNotNull { id ->
      when {
        id == self -> {
          null
        }
        id.startsWith(":") -> {
          val other = known[id]
          when {
            other == null -> "illegal project dependency: $id (it is on no layer)"
            other in allowed -> null
            else -> "illegal project dependency: $id ($mine may not reach $other)"
          }
        }
        allowedCoordinates.any { id == it || id.startsWith("$it-") } -> {
          null
        }
        else -> {
          forbidden
            .firstOrNull { id == it || id.startsWith("$it:") || id.startsWith("$it.") }
            ?.let { rule -> "forbidden coordinate: $id (rule '$rule')" }
        }
      }
    }
  }
}
