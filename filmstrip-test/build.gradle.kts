import dev.jordond.filmstrip.convention.pureTests

plugins {
  id("filmstrip.library")
}

pureTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      implementation(kotlin("test"))
    }

    androidMain.dependencies {
      compileOnly(libs.junit)
    }
  }
}
