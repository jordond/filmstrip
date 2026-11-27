import dev.jordond.filmstrip.convention.androidDeviceTests

plugins {
  id("filmstrip.library.compose")
}

androidDeviceTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCompose)
      api(projects.filmstripEffects)

      api(libs.compose.runtime)
      api(libs.compose.ui)
      api(libs.compose.foundation)
      implementation(libs.compose.ui.tooling.preview)
    }

    jvmTest {
      // The parity contract the device suite runs too.
      kotlin.srcDir("src/composeUiTest/kotlin")
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.compose.ui.test)
        implementation(libs.kotlinx.coroutines.test)
      }
    }

    named("androidDeviceTest") {
      kotlin.srcDir("src/composeUiTest/kotlin")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotest.assertions)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.androidx.test.runner)
        implementation(libs.androidx.activity.compose)
        implementation(libs.compose.ui.test)
        // Compose's ui-test brings Espresso 3.5, which reaches InputManager.getInstance by reflection. Android 17 has
        // no such method.
        implementation(libs.androidx.test.espresso)
      }
    }
  }
}

dependencies {
  androidRuntimeClasspath(libs.compose.ui.tooling)
}

// The Compose plugin registers this copy for every Android component, but only gives it somewhere to write when the
// component has assets, and a KMP library's device test has none. This module ships no Compose resources to copy.
tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }.configureEach {
  enabled = false
}
