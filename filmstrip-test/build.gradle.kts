import dev.jordond.filmstrip.convention.pureTests

plugins {
  id("filmstrip.library")
}

pureTests()

kotlin {
  sourceSets {
    // Core's tests compile these directories in too, so nothing in them may reach the rest of this module.
    commonMain {
      kotlin.srcDir("src/primedAudio/kotlin")
      kotlin.srcDir("src/animatedPictures/kotlin")
    }

    commonMain.dependencies {
      api(projects.filmstripCore)
      implementation(kotlin("test"))
    }

    androidMain.dependencies {
      compileOnly(libs.junit)
      implementation(libs.media3.common)
      compileOnly(libs.media3.inspector)
      compileOnly(libs.media3.inspector.frame)
      compileOnly(libs.androidx.test.runner)
    }
  }
}
