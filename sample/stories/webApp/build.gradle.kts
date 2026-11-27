import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("filmstrip.internal")
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(
    libs.versions.jvmTarget
      .get()
      .toInt(),
  )

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName = "stories"
    browser {
      commonWebpackConfig {
        outputFileName = "stories.js"
      }
    }
    binaries.executable()
  }

  sourceSets {
    wasmJsMain.dependencies {
      implementation(projects.sample.stories.shared)
      implementation(libs.compose.runtime)
      implementation(libs.compose.ui)
    }
  }
}
