import dev.jordond.filmstrip.convention.testmedia.FixturePatch
import dev.jordond.filmstrip.convention.testmedia.FixtureSpec
import dev.jordond.filmstrip.convention.testmedia.FixtureTransfer
import dev.jordond.filmstrip.convention.testmedia.testMedia

plugins {
  id("filmstrip.library.apple")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripTransform)
    }

    commonTest.dependencies {
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

// Only the host gets the fixtures. simctl forwards nothing without a SIMCTL_CHILD_ prefix, so a
// simulator run reads no path and skips. A simulator would only answer questions about its own
// codecs anyway.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
  if (name == "macosArm64Test") {
    dependsOn(appleFixtures.download)
    environment("FILMSTRIP_FIXTURES", appleFixtures.directory.absolutePath)
  }
}
