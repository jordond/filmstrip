import dev.jordond.filmstrip.convention.androidDeviceTests

plugins {
  id("filmstrip.library")
  alias(libs.plugins.kotlin.serialization)
}

androidDeviceTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
    }

    androidMain {
      languageSettings.optIn("androidx.media3.common.util.UnstableApi")
      dependencies {
        api(libs.media3.effect)
        api(libs.media3.common)
        implementation(libs.androidx.core)
      }
    }

    named("androidDeviceTest").dependencies {
      implementation(libs.androidx.test.runner)
    }
  }
}
