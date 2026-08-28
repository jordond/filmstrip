import dev.jordond.filmstrip.convention.androidDeviceTests

plugins {
  id("filmstrip.library")
}

androidDeviceTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripEffects)
    }

    androidMain.dependencies {
      api(libs.media3.exoplayer)
      api(libs.media3.ui)
      api(libs.media3.common)
    }

    named("androidDeviceTest").dependencies {
      implementation(libs.androidx.test.runner)
    }
  }

  sourceSets.androidMain {
    languageSettings.optIn("androidx.media3.common.util.UnstableApi")
  }
}
