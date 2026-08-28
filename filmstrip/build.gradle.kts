plugins {
  id("filmstrip.library")
}

// The convenience artifact: every backend, one dependency, one call. Deliberately not Compose.
kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripEffects)
      api(projects.filmstripPlayer)
      api(projects.filmstripTransform)
    }

    androidMain.dependencies {
      api(projects.filmstripTransformMedia3)
    }

    appleMain.dependencies {
      api(projects.filmstripTransformAvfoundation)
    }

    webMain.dependencies {
      api(projects.filmstripTransformWebcodecs)
    }

    jvmMain.dependencies {
      api(projects.filmstripTransformFfmpeg)
    }
  }
}
