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
    outputModuleName = "sample"
    browser {
      commonWebpackConfig {
        outputFileName = "sample.js"
      }
      testTask {
        useKarma {
          useChromeHeadless()
        }
      }
    }
    binaries.executable()
  }

  sourceSets {
    wasmJsMain.dependencies {
      implementation(projects.filmstrip)
      implementation(projects.sample.common)
      implementation(projects.filmstripEffectsFilters)
      implementation(projects.filmstripCompose)
      implementation(projects.filmstripComposeUi)
      implementation(projects.filmstripIoFilekit)
      implementation(projects.filmstripIoFilekitCompose)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.compose.ui)
      implementation(libs.compose.adaptive)
      implementation(libs.compose.adaptive.layout)
      implementation(libs.navigation3.runtime)
      implementation(libs.navigation3.ui)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.browser)
    }

    wasmJsTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotest.assertions)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.compose.ui.test)
    }
  }
}
