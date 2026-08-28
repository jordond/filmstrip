package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Guard C. Catches what Guard B cannot see: a npm package.
 */
abstract class CheckNpmDependenciesTask : DefaultTask() {
  @get:Input
  abstract val moduleName: Property<String>

  @get:Input
  abstract val allowedPackages: SetProperty<String>

  /**
   * `name@version` for every npm dependency found, captured at configuration time.
   */
  @get:Input
  abstract val declaredPackages: SetProperty<String>

  @TaskAction
  fun check() {
    val allowed = allowedPackages.get()
    val violations = declaredPackages.get().sorted().filter { it.substringBeforeLast('@') !in allowed }

    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("Layering violation: npm dependencies in ${moduleName.get()} (${violations.size}):")
          violations.forEach { appendLine("  - $it") }
        },
      )
    }
  }
}
