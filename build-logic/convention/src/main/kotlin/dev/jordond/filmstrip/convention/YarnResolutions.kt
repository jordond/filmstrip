package dev.jordond.filmstrip.convention

import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

/**
 * Moves the packages the browser test tooling pulls in past versions with open advisories.
 *
 * Kotlin pins mocha and karma-webpack, and both ask for majors of these that are fixed only in a later one. Each entry
 * is scoped to the package that asks, so karma keeps the versions it runs on. Drop an entry once the lock resolves past it without one.
 */
internal fun Project.configureYarnResolutions() {
  plugins.withType<YarnPlugin> {
    the<YarnRootExtension>().apply {
      resolution("**/mocha/diff", "8.0.3")
      resolution("**/mocha/glob", "14.0.2")
      resolution("**/mocha/minimatch", "11.0.1")
      resolution("**/mocha/serialize-javascript", "7.1.4")
      resolution("**/karma-webpack/minimatch", "11.0.1")
    }
  }
}
