import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("filmstrip.internal")
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.android.multiplatform.library)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(
    libs.versions.jvmTarget
      .get()
      .toInt(),
  )

  android {
    namespace = "dev.jordond.filmstrip.sample.stories.shared"
    compileSdk =
      libs.versions.sdk.compile
        .get()
        .toInt()
    minSdk =
      libs.versions.sdk.min
        .get()
        .toInt()
  }

  jvm()

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "Shared"
      isStatic = true
    }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {

    commonMain.dependencies {
      api(projects.filmstrip)
      api(projects.sample.common)
      implementation(projects.filmstripEffectsFilters)
      implementation(projects.filmstripCompose)
      implementation(projects.filmstripComposeUi)
      implementation(projects.filmstripIoFilekit)
      implementation(projects.filmstripIoFilekitCompose)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.compose.components.resources)
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
    }

    androidMain.dependencies {
      implementation(libs.androidx.activity.compose)
      implementation(libs.kotlinx.coroutines.android)
    }

    wasmJsMain.dependencies {
      implementation(libs.kotlinx.browser)
    }

    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.swing)
    }

    jvmTest.dependencies {
      implementation(libs.kotest.assertions)
      implementation(libs.compose.ui.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(projects.filmstripTesting)
    }
  }
}

compose.resources {
  packageOfResClass = "dev.jordond.filmstrip.sample.stories.resources"
}
