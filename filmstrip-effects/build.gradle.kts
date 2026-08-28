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

    androidMain.dependencies {
      api(libs.media3.effect)
      api(libs.media3.common)
      // NOT media3-transformer or media3-exoplayer, checkLayeringFilmstripEffects fails the build.
    }

    // appleMain: Core Image ships with Kotlin/Native.

    named("androidDeviceTest").dependencies {
      implementation(libs.androidx.test.runner)
    }
  }

  // Media3's effect surface is almost entirely @UnstableApi, so scope the opt-in to androidMain.
  sourceSets.androidMain {
    languageSettings.optIn("androidx.media3.common.util.UnstableApi")
  }
}
