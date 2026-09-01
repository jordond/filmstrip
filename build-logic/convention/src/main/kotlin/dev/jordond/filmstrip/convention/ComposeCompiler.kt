package dev.jordond.filmstrip.convention

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Turns on the Compose compiler's own reports when `composeMetrics` is set.
 *
 * They land in `<module>/build/compose-reports` and say which composables skip and which parameters
 * the compiler could not read as stable.
 */
internal fun Project.configureComposeCompiler() {
  extensions.configure<ComposeCompilerGradlePluginExtension> {
    if (providers.gradleProperty("composeMetrics").isPresent) {
      reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
      metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    }
  }
}
