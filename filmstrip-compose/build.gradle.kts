plugins {
  id("filmstrip.library.compose")
}

// The only artifact with Compose on the classpath.
kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripPlayer)

      implementation(compose.runtime)
      implementation(compose.foundation)
    }
  }
}
