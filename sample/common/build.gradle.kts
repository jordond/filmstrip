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
    browser()
  }

  sourceSets {
    commonMain.dependencies {
      api(projects.filmstrip)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.adaptive)
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
    }

    wasmJsMain.dependencies {
      implementation(libs.kotlinx.browser)
    }

    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotest.assertions)
    }
  }
}

compose.resources {
  packageOfResClass = "dev.jordond.filmstrip.sample.common.resources"
}

// The token test reads these, so a change to any of them has to rerun it.
tasks.named<Test>("jvmTest") {
  inputs
    .files(
      rootProject.file("website/src/styles/tokens.css"),
      rootProject.file("website/src/styles/crt.css"),
      rootProject.file("sample/webApp/src/wasmJsMain/resources/index.html"),
      rootProject.file("sample/tryIt/src/wasmJsMain/resources/index.html"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("siteTokens")
  systemProperty("filmstrip.rootDir", rootProject.projectDir.absolutePath)
}
