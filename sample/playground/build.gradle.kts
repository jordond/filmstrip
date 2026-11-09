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

  jvm()

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName = "playground"
    browser {
      commonWebpackConfig {
        outputFileName = "playground.js"
      }
    }
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.sample.common)
      implementation(projects.filmstripCompose)
      implementation(projects.filmstripEffectsFilters)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.compose.ui)
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotest.assertions)
    }

    wasmJsMain.dependencies {
      implementation(libs.kotlinx.browser)
    }
  }
}
