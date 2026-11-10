import dev.jordond.filmstrip.convention.androidDeviceTests
import dev.jordond.filmstrip.convention.bootIosSimulatorForTests
import dev.jordond.filmstrip.convention.pureTests

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

    commonTest.dependencies {
      implementation(projects.filmstripTest)
    }

    androidMain {
      dependencies {
        implementation(libs.androidx.core)
      }
    }

    named("androidHostTest").dependencies {
      implementation(projects.filmstripTest)
    }

    // Tests that need ImageIO to write a HEVC image sequence, which the simulator does not. macOS runs them. An iPhone
    // run takes them too and fails if its ImageIO writes no sequence.
    val appleHardwareTest = create("appleHardwareTest") { dependsOn(getByName("appleTest")) }
    macosArm64Test.get().dependsOn(appleHardwareTest)
    iosArm64Test.get().dependsOn(appleHardwareTest)

    named("androidDeviceTest").dependencies {
      implementation(kotlin("test"))
      implementation(libs.androidx.test.runner)
      implementation(projects.filmstripTest)
      implementation(libs.kotest.assertions)
    }
  }
}

bootIosSimulatorForTests()

// ffmpeg-text-overlay: the JVM text raster is drawn the way a server draws it, with no display to reach.
tasks.named<Test>("jvmTest") {
  systemProperty("java.awt.headless", "true")
}
