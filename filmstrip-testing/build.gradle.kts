plugins {
  id("filmstrip.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(libs.kotlinx.coroutines.test)
      implementation(projects.filmstripPlayer)
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotest.assertions)
    }
  }
}
