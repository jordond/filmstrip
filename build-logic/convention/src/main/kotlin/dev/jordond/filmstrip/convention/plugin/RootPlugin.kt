package dev.jordond.filmstrip.convention.plugin

import dev.jordond.filmstrip.convention.configureArchitectureGuards
import dev.jordond.filmstrip.convention.configureSpotless
import org.gradle.api.Plugin
import org.gradle.api.Project

class RootPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.configureSpotless()
    target.configureArchitectureGuards()
  }
}
