import dev.jordond.filmstrip.convention.testmedia.FixtureSpec
import dev.jordond.filmstrip.convention.testmedia.FixtureTransfer
import dev.jordond.filmstrip.convention.testmedia.GenerateTestMediaTask

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

val hasFfmpeg =
  providers.environmentVariable("PATH").orElse("").map { path ->
    path.split(File.pathSeparatorChar).any { entry -> File(entry, "ffmpeg").canExecute() }
  }

// The clips the Apple host tests export. AVFoundation, VideoToolbox and the filesystem are all real
// on macOS, so the whole pipeline runs here in full.
val appleFixtures =
  tasks.register<GenerateTestMediaTask>("generateAppleTestFixtures") {
    group = "verification"
    description = "Generates the clips the Apple export host test encodes."
    specs.set(
      listOf(
        FixtureSpec("apple_export_a", 640, 360, 30, 2.0, 48000, 2, bitrateKbps = 1_500),
        FixtureSpec("apple_export_b", 480, 270, 30, 2.0, 48000, 2, bitrateKbps = 1_000, hue = 120),
        FixtureSpec("apple_export_portrait", 360, 640, 30, 2.0, 48000, 2, bitrateKbps = 1_500, hue = 240),
        // Long enough that a hardware encode outlives the progress interval, which a two second
        // clip does not.
        FixtureSpec("apple_export_long", 1280, 720, 30, 12.0, 48000, 2, bitrateKbps = 6_000),
        // Ten-bit BT.2020 PQ, so the HDR branch has something to run against.
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
        ),
      ),
    )
    outputDirectory.set(layout.buildDirectory.dir("apple-test-fixtures"))
    manifest.set(layout.buildDirectory.file("apple-test-fixtures/fixtures.txt"))
  }

// Only the host gets the fixtures. simctl forwards nothing without a SIMCTL_CHILD_ prefix, so a
// simulator run reads no path and skips. A simulator would only answer questions about its own
// codecs anyway.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
  if (name == "macosArm64Test" && hasFfmpeg.get()) {
    dependsOn(appleFixtures)
    environment(
      "FILMSTRIP_FIXTURES",
      layout.buildDirectory
        .dir("apple-test-fixtures")
        .get()
        .asFile.absolutePath,
    )
  }
}
