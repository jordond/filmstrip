import dev.jordond.filmstrip.convention.androidDeviceTests

plugins {
  id("filmstrip.library")
}

androidDeviceTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
    }

    commonTest.dependencies {
      implementation(projects.filmstripTest)
    }

    androidMain {
      languageSettings.optIn("androidx.media3.common.util.UnstableApi")
      dependencies {
        api(libs.media3.effect)
        api(libs.media3.common)
        implementation(libs.androidx.core)
      }
    }

    named("androidHostTest").dependencies {
      implementation(projects.filmstripTest)
    }

    named("androidDeviceTest").dependencies {
      implementation(kotlin("test"))
      implementation(libs.androidx.test.runner)
      implementation(projects.filmstripTest)
    }
  }
}
