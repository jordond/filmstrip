plugins {
  id("filmstrip.library.web")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripEffects)
      api(projects.filmstripTransform)
      implementation(npm("mediabunny", "1.55.1"))
    }
  }
}
