plugins {
  id("filmstrip.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      implementation(kotlin("test"))
    }
  }
}
