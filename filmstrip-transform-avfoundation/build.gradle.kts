import dev.jordond.filmstrip.convention.bootIosSimulatorForTests
import dev.jordond.filmstrip.convention.testmedia.FixturePatch
import dev.jordond.filmstrip.convention.testmedia.FixtureSpec
import dev.jordond.filmstrip.convention.testmedia.FixtureTransfer
import dev.jordond.filmstrip.convention.testmedia.testMedia
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
  id("filmstrip.library.apple")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripTransform)
    }

    commonTest.dependencies {
      implementation(projects.filmstripTest)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

val appleFixtures =
  testMedia(
    name = "apple",
    specs =
      listOf(
        FixtureSpec("apple_export_a", 640, 360, 30, 2.0, 48000, 2, bitrateKbps = 1_500),
        // A bed to mix under the others. Its tone is the one frequency no other fixture carries,
        // so a test measuring the mix can say which source a level belongs to.
        FixtureSpec("tone_bed_880", 320, 240, 30, 3.0, 48000, 2, bitrateKbps = 500, toneHz = 880),
        FixtureSpec("apple_export_b", 480, 270, 30, 2.0, 48000, 2, bitrateKbps = 1_000, hue = 120),
        FixtureSpec("apple_export_portrait", 360, 640, 30, 2.0, 48000, 2, bitrateKbps = 1_500, hue = 240),
        FixtureSpec("apple_export_long", 1280, 720, 30, 12.0, 48000, 2, bitrateKbps = 6_000),
        FixtureSpec(
          "apple_export_hdr",
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
        FixtureSpec(
          "apple_export_hdr_hlg",
          1280,
          720,
          30,
          2.0,
          48000,
          2,
          bitrateKbps = 6_000,
          transfer = FixtureTransfer.Hlg,
          patch = FixturePatch.Graded,
        ),
      ),
  )

// Both Apple test targets read the fixtures off the host filesystem, which a simulator process can
// see. simctl hands a spawned process only the variables prefixed for it and strips the prefix on
// the way in, so the simulator task sets the same name behind SIMCTL_CHILD_.
tasks.withType<KotlinNativeTest>().configureEach {
  when (name) {
    "macosArm64Test" -> {
      dependsOn(appleFixtures.download)
      environment("FILMSTRIP_FIXTURES", appleFixtures.directory.absolutePath)
    }
    "iosSimulatorArm64Test" -> {
      dependsOn(appleFixtures.download)
      environment("SIMCTL_CHILD_FILMSTRIP_FIXTURES", appleFixtures.directory.absolutePath)
    }
  }
}

bootIosSimulatorForTests()
