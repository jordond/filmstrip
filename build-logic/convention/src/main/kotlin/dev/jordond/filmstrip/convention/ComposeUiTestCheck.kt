package dev.jordond.filmstrip.convention

import org.gradle.api.Project

private val WEB_TARGETS =
  mapOf(
    "Js" to listOf("commonTest", "webTest", "jsTest"),
    "WasmJs" to listOf("commonTest", "webTest", "wasmJsTest"),
  )

/**
 * Skips the Compose web UI test check on a module that has no tests for it to look at.
 *
 * The check is registered on every web target whether or not the module has any, and it fails one
 * that has none. Declaring `binaries.executable()` to satisfy it would put a webpack bundle in a
 * published library. The check runs again on its own once one of the source sets that target
 * compiles carries a file.
 */
internal fun Project.configureComposeUiTestCheck() {
  WEB_TARGETS.forEach { (target, sourceSets) ->
    val sources = objects.fileCollection().from(sourceSets.map { layout.projectDirectory.dir("src/$it") })
    tasks.matching { it.name == "checkComposeUiTestConfigurationFor$target" }.configureEach {
      onlyIf { !sources.asFileTree.filter { file -> file.extension == "kt" }.isEmpty }
    }
  }
}
