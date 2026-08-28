package dev.jordond.filmstrip.convention.plugin

import dev.jordond.filmstrip.convention.configureArchitectureGuards
import dev.jordond.filmstrip.convention.configureComposeUiTestCheck
import dev.jordond.filmstrip.convention.configureSpotless
import org.gradle.api.Plugin
import org.gradle.api.Project

class RootPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.configureSpotless()
    target.configureArchitectureGuards()

    // Registered by the Compose plugin, which the sample modules apply directly rather than
    // through a convention of their own.
    target.allprojects { configureComposeUiTestCheck() }
  }
}
