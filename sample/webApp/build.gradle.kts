import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(libs.versions.jvmTarget.get().toInt())

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName = "sample"
    browser {
      commonWebpackConfig {
        outputFileName = "sample.js"
      }
    }
    binaries.executable()
  }

  sourceSets {
    wasmJsMain.dependencies {
      implementation(projects.sample.shared)
      implementation(libs.compose.runtime)
      implementation(libs.compose.ui)
    }
  }
}
