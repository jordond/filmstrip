plugins {
  id("filmstrip.library")
}

// Test fixtures. Exempt from the layering map.
kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      implementation(kotlin("test"))
    }
  }
}
