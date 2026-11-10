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

    named("androidDeviceTest").dependencies {
      implementation(kotlin("test"))
      implementation(libs.androidx.test.runner)
      implementation(libs.kotest.assertions)
    }
  }
}

bootIosSimulatorForTests()

tasks.named<Test>("jvmTest") {
  usesHostFfmpeg()
}
