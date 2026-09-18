plugins {
  id("filmstrip.library.compose")
}

kotlin {
  sourceSets {
    create("nonWebMain") {
      dependsOn(commonMain.get())
      androidMain.get().dependsOn(this)
      appleMain.get().dependsOn(this)
      jvmMain.get().dependsOn(this)
    }

    create("mobileMain") {
      dependsOn(commonMain.get())
      androidMain.get().dependsOn(this)
      iosMain.get().dependsOn(this)
    }

    commonMain.dependencies {
      api(projects.filmstripIoFilekit)
      api(libs.filekit.dialogs.compose)

      implementation(libs.compose.runtime)
    }

    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
    }
  }
}
