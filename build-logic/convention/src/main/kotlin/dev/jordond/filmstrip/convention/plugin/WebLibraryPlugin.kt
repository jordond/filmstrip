package dev.jordond.filmstrip.convention.plugin

import dev.jordond.filmstrip.convention.configureFilmstripLibrary
import dev.jordond.filmstrip.convention.configureSpotless
import org.gradle.api.Plugin
import org.gradle.api.Project

class WebLibraryPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target.pluginManager) {
      apply("org.jetbrains.kotlin.multiplatform")
      apply("dev.drewhamilton.poko")
      apply("org.jetbrains.dokka")
      apply("com.vanniktech.maven.publish")
      apply("org.jetbrains.kotlinx.kover")
    }

    target.configureSpotless()
    target.configureFilmstripLibrary(android = false, apple = false, macOs = false, web = true, jvm = false)
  }
}
