import dev.jordond.filmstrip.convention.androidDeviceTests

plugins {
  id("filmstrip.library")
}

androidDeviceTests()

kotlin {
  sourceSets {
    create("nonWebMain") {
      dependsOn(commonMain.get())
      androidMain.get().dependsOn(this)
      appleMain.get().dependsOn(this)
      jvmMain.get().dependsOn(this)
    }

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
