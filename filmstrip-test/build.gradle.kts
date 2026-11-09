import dev.jordond.filmstrip.convention.pureTests

plugins {
  id("filmstrip.library")
}

pureTests()

kotlin {
  sourceSets {
    // Core's tests compile this directory in too, so nothing in it may reach the rest of this module.
    commonMain { kotlin.srcDir("src/primedAudio/kotlin") }

    commonMain.dependencies {
      api(projects.filmstripCore)
      implementation(kotlin("test"))
    }

    androidMain.dependencies {
      compileOnly(libs.junit)
    }
  }
}
