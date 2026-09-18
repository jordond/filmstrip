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

    // Only wasmJs needs this. FileKit's browser file type extends `org.w3c.files.Blob`, which the
    // js standard library carries and the wasm one does not.
    named("wasmJsTest").dependencies {
      implementation(libs.kotlinx.browser)
    }

    named("androidDeviceTest").dependencies {
      implementation(kotlin("test"))
      implementation(libs.androidx.test.runner)
    }
  }
}
