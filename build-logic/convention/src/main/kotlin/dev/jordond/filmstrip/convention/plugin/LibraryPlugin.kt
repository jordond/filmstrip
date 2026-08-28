package dev.jordond.filmstrip.convention.plugin

import dev.jordond.filmstrip.convention.configureFilmstripLibrary
import dev.jordond.filmstrip.convention.configureSpotless
import org.gradle.api.Plugin
import org.gradle.api.Project

class LibraryPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target.pluginManager) {
      apply("org.jetbrains.kotlin.multiplatform")
      apply("com.android.kotlin.multiplatform.library")
      apply("dev.drewhamilton.poko")
      apply("org.jetbrains.dokka")
      apply("com.vanniktech.maven.publish")
      apply("org.jetbrains.kotlinx.kover")
    }

    target.configureSpotless()
    target.configureFilmstripLibrary(macOs = true, web = true, jvm = true)
  }
}
