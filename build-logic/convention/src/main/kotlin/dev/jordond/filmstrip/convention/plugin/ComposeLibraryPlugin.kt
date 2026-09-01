package dev.jordond.filmstrip.convention.plugin

import dev.jordond.filmstrip.convention.configureComposeCompiler
import dev.jordond.filmstrip.convention.configureFilmstripLibrary
import dev.jordond.filmstrip.convention.configureSpotless
import org.gradle.api.Plugin
import org.gradle.api.Project

class ComposeLibraryPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target.pluginManager) {
      apply("org.jetbrains.kotlin.multiplatform")
      apply("com.android.kotlin.multiplatform.library")
      apply("org.jetbrains.kotlin.plugin.compose")
      apply("org.jetbrains.compose")
      apply("dev.drewhamilton.poko")
      apply("org.jetbrains.dokka")
      apply("com.vanniktech.maven.publish")
      apply("org.jetbrains.kotlinx.kover")
    }

    target.configureComposeCompiler()
    target.configureSpotless()
    target.configureFilmstripLibrary(macOs = false, web = true, jvm = true)
  }
}
