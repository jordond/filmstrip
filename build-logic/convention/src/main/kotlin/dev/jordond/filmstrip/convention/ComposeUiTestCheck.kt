package dev.jordond.filmstrip.convention

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

private const val CHECK_TASK_PREFIX = "checkComposeUiTestConfigurationFor"
private const val UI_TEST_GROUP = "org.jetbrains.compose.ui"
private const val UI_TEST_MODULE = "ui-test"

/**
 * Runs the Compose web UI test check only for a web target whose test compilation resolves Compose UI test.
 *
 * The Compose plugin registers `checkComposeUiTestConfigurationFor<Target>` on every web target and fails it when the
 * test classpath carries Skiko but the target declares no `binaries.executable()`. Skiko comes along with any Compose
 * UI library, so a module whose web tests only drive the Compose runtime would fail it too. This skips the check
 * unless `org.jetbrains.compose.ui:ui-test` is on that target's test compile classpath, which is what
 * `runComposeUiTest` needs and what loads the Skiko bundle. A target with no web tests resolves no `ui-test` either,
 * so its check is skipped as well.
 */
internal fun Project.configureComposeUiTestCheck() {
  tasks.matching { it.name.startsWith(CHECK_TASK_PREFIX) }.configureEach {
    val targetName = name.removePrefix(CHECK_TASK_PREFIX).replaceFirstChar { it.lowercase() }
    val target =
      project.extensions
        .getByType(KotlinMultiplatformExtension::class.java)
        .targets
        .getByName(targetName)
    val compilation = target.compilations.getByName(KotlinCompilation.TEST_COMPILATION_NAME)
    val resolvesUiTest =
      project.configurations
        .named(compilation.compileDependencyConfigurationName)
        .flatMap { it.incoming.resolutionResult.rootComponent }
        .map { it.reachesUiTest() }
    onlyIf("the web tests resolve Compose UI test") { resolvesUiTest.get() }
  }
}

private fun ResolvedComponentResult.reachesUiTest(): Boolean {
  val seen = mutableSetOf(this)
  val queue = ArrayDeque(listOf(this))
  while (queue.isNotEmpty()) {
    val component = queue.removeFirst()
    val id = component.id
    if (id is ModuleComponentIdentifier && id.group == UI_TEST_GROUP && id.module == UI_TEST_MODULE) return true
    component.dependencies
      .filterIsInstance<ResolvedDependencyResult>()
      .map { it.selected }
      .filter(seen::add)
      .forEach(queue::addLast)
  }
  return false
}
