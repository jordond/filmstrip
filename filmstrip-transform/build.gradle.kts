import dev.jordond.filmstrip.convention.pureTests

plugins {
  id("filmstrip.library")
}

pureTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripEffects)
    }

    commonTest.dependencies {
      implementation(projects.filmstripTest)
      implementation(projects.filmstripEffectsFilters)
    }
  }
}
