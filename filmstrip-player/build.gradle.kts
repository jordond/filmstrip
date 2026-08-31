import dev.jordond.filmstrip.convention.androidDeviceTests
import dev.jordond.filmstrip.convention.bootIosSimulatorForTests
import dev.jordond.filmstrip.convention.testmedia.FixturePatch
import dev.jordond.filmstrip.convention.testmedia.FixtureSpec
import dev.jordond.filmstrip.convention.testmedia.FixtureTransfer
import dev.jordond.filmstrip.convention.testmedia.testMedia
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
  id("filmstrip.library")
}

androidDeviceTests()

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripEffects)
      api(projects.filmstripTransform)
    }

    androidMain.dependencies {
      api(libs.media3.exoplayer)
      api(libs.media3.ui)
      api(libs.media3.common)
      implementation(libs.media3.inspector.frame)
      api(projects.filmstripTransformMedia3)
      implementation(libs.androidx.core)
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

    commonTest.dependencies {
      implementation(projects.filmstripTest)
    }

    named("androidDeviceTest") {
      kotlin.srcDir("src/commonTest/kotlin/dev/jordond/filmstrip/playback/contract")
      dependencies {
        implementation(kotlin("test"))
        implementation(projects.filmstripTest)
        implementation(libs.kotest.assertions)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.androidx.test.runner)
        implementation(libs.androidx.test.core)
      }
    }
  }

  sourceSets.androidMain {
    languageSettings.optIn("androidx.media3.common.util.UnstableApi")
  }
}

val playerFixtures =
  testMedia(
    name = "player",
    specs =
      listOf(
        FixtureSpec("apple_export_a", 640, 360, 30, 2.0, 48000, 2, bitrateKbps = 1_500),
        FixtureSpec(
          "android_export_hdr",
          1280,
          720,
          30,
          2.0,
          48000,
          2,
          bitrateKbps = 6_000,
          transfer = FixtureTransfer.Pq,
          patch = FixturePatch.Graded,
        ),
      ),
  )

tasks.withType<KotlinNativeTest>().configureEach {
  when (name) {
    "macosArm64Test" -> {
      dependsOn(playerFixtures.download)
      environment("FILMSTRIP_FIXTURES", playerFixtures.directory.absolutePath)
    }
    "iosSimulatorArm64Test" -> {
      dependsOn(playerFixtures.download)
      environment("SIMCTL_CHILD_FILMSTRIP_FIXTURES", playerFixtures.directory.absolutePath)
    }
  }
}

bootIosSimulatorForTests()

tasks.named<Test>("jvmTest") {
  systemProperty("filmstrip.fixtures", playerFixtures.directory.absolutePath)
  dependsOn(playerFixtures.download)
}

kotlin.sourceSets.named("androidDeviceTest") {
  resources.srcDir(playerFixtures.download.map { it.outputDirectory })
}
