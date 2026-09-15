plugins {
  id("filmstrip.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripEffects)
    }

    commonTest.dependencies {
      implementation(projects.filmstripTest)
    }
  }
}
