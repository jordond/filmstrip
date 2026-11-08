import dev.jordond.filmstrip.convention.androidDeviceTests
import dev.jordond.filmstrip.convention.bootIosSimulatorForTests
import dev.jordond.filmstrip.convention.pureTests
import dev.jordond.filmstrip.convention.usesHostFfmpeg

plugins {
  id("filmstrip.library")
}

androidDeviceTests()
pureTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
    }

    appleMain.dependencies {
      implementation(projects.filmstripEffects)
    }

    androidMain {
      languageSettings.optIn("androidx.media3.common.util.UnstableApi")
    }

    named("androidDeviceTest") {
      languageSettings.optIn("androidx.media3.common.util.UnstableApi")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.androidx.test.runner)
        implementation(libs.kotest.assertions)
      }
    }
  }
}

bootIosSimulatorForTests()

tasks.named<Test>("jvmTest") {
  usesHostFfmpeg()
}
