import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The Kotlin the docs site shows. Pages pull `// region` blocks out of these sources, so every snippet compiles.
plugins {
  id("filmstrip.internal")
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.android.multiplatform.library)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(
    libs.versions.jvmTarget
      .get()
      .toInt(),
  )

  android {
    namespace = "dev.jordond.filmstrip.docs"
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

  iosArm64()

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      implementation(projects.filmstrip)
      implementation(projects.filmstripCompose)
      implementation(projects.filmstripComposeUi)
      implementation(projects.filmstripEffectsFilters)
      implementation(projects.filmstripIoFilekit)
      implementation(projects.filmstripIoFilekitCompose)
      implementation(projects.filmstripIoOkio)
      implementation(projects.filmstripIoKotlinx)
      implementation(projects.filmstripTesting)
      implementation(libs.compose.runtime)
    }

    jvmMain.dependencies {
      implementation(projects.filmstripTransformFfmpegBundled)
    }
  }
}
