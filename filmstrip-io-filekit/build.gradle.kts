import dev.jordond.filmstrip.convention.androidDeviceTests

plugins {
  id("filmstrip.library")
}

androidDeviceTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(libs.filekit.core)
    }

    named("androidDeviceTest").dependencies {
      implementation(kotlin("test"))
      implementation(libs.androidx.test.runner)
    }
  }
}
