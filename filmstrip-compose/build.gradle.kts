import dev.jordond.filmstrip.convention.androidDeviceTests
import dev.jordond.filmstrip.convention.bootIosSimulatorForTests
import dev.jordond.filmstrip.convention.testmedia.FixtureSpec
import dev.jordond.filmstrip.convention.testmedia.testMedia

plugins {
  id("filmstrip.library.compose")
}

androidDeviceTests()

kotlin {
  sourceSets {
    create("skiaMain") {
      dependsOn(commonMain.get())
      jvmMain.get().dependsOn(this)
      webMain.get().dependsOn(this)
      iosMain.get().dependsOn(this)
    }

    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripPlayer)

      api(libs.compose.runtime)
      api(libs.compose.ui)
      implementation(libs.compose.foundation)
    }

    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
    }

    iosTest.dependencies {
      implementation(libs.compose.ui.test)
    }

    named("androidDeviceTest") {
      // The fakes the host suites share, so the device suite drives the same engine they do.
      kotlin.srcDir("src/commonTest/kotlin")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotest.assertions)
        implementation(projects.filmstripTest)
        implementation(libs.androidx.test.runner)
        implementation(libs.androidx.activity.compose)
        implementation(libs.compose.ui.test)
        // Compose's ui-test brings Espresso 3.5, which reaches InputManager.getInstance by reflection. Android 17 has
        // no such method.
        implementation(libs.androidx.test.espresso)
      }
    }
  }
}

bootIosSimulatorForTests()

val surfaceFixtures =
  testMedia(
    name = "surface",
    specs = listOf(FixtureSpec("android_export_a", 640, 360, 30, 2.0, 48000, 2, bitrateKbps = 1_500)),
  )

kotlin.sourceSets.named("androidDeviceTest") {
  resources.srcDir(surfaceFixtures.download.map { it.outputDirectory })
}

// The Compose plugin registers this copy for every Android component, but only gives it somewhere to write when the
// component has assets, and a KMP library's device test has none. This module ships no Compose resources to copy.
tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }.configureEach {
  enabled = false
}
