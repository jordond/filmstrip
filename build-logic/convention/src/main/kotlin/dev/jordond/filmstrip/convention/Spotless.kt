package dev.jordond.filmstrip.convention

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

internal fun Project.configureSpotless() {
  val editorConfig =
    rootProject.layout.projectDirectory
      .file(".editorconfig")
      .asFile.path
  val ktlintVersion = version("ktlint")

  pluginManager.apply("com.diffplug.spotless")

  extensions.configure<SpotlessExtension> {
    kotlin {
      ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
      target(fileTree("src") { include("**/*.kt") })
    }

    kotlinGradle {
      ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
      target("*.gradle.kts")
    }
  }
}

// build-logic is a separate included build, so its spotlessCheck doesn't run just because the
// root project's does. Wire it in so both do.
internal fun Project.configureBuildLogicSpotless() {
  val buildLogic = gradle.includedBuild("build-logic")

  tasks.named("spotlessCheck") {
    dependsOn(buildLogic.task(":convention:spotlessCheck"))
  }

  tasks.named("spotlessApply") {
    dependsOn(buildLogic.task(":convention:spotlessApply"))
  }
}
