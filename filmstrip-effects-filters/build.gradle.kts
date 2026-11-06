import dev.jordond.filmstrip.convention.androidDeviceTests
import dev.jordond.filmstrip.convention.bootIosSimulatorForTests

plugins {
  id("filmstrip.library")
}

androidDeviceTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
    }

    appleMain.dependencies {
      implementation(projects.filmstripEffects)
    }

    androidMain {
      languageSettings.optIn("androidx.media3.common.util.UnstableApi")
    }

    named("androidDeviceTest") {
      languageSettings.optIn("androidx.media3.common.util.UnstableApi")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.androidx.test.runner)
        implementation(libs.kotest.assertions)
      }
    }
  }
}

bootIosSimulatorForTests()
