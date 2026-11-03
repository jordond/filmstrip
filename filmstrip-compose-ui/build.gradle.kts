plugins {
  id("filmstrip.library.compose")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCompose)

      api(libs.compose.runtime)
      api(libs.compose.ui)
      api(libs.compose.foundation)
      implementation(libs.compose.ui.tooling.preview)
    }

    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

dependencies {
  androidRuntimeClasspath(libs.compose.ui.tooling)
}
