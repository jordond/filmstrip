plugins {
  id("filmstrip.library.compose")
}

kotlin {
  sourceSets {
    create("skiaMain") {
      dependsOn(commonMain.get())
      jvmMain.get().dependsOn(this)
      webMain.get().dependsOn(this)
      iosMain.get().dependsOn(this)
    }

    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripPlayer)

      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
    }

    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
    }
  }
}
