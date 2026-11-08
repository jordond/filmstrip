package dev.jordond.filmstrip.convention.plugin

import dev.jordond.filmstrip.convention.configureBrowserTestsOnMultiplatform
import dev.jordond.filmstrip.convention.configureIosFloor
import dev.jordond.filmstrip.convention.configureSpotless
import org.gradle.api.Plugin
import org.gradle.api.Project

class InternalPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.configureSpotless()
    target.configureIosFloor()
    target.configureBrowserTestsOnMultiplatform()
  }
}
