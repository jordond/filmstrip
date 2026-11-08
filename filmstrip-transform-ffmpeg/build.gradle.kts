import dev.jordond.filmstrip.convention.testmedia.FixturePatch
import dev.jordond.filmstrip.convention.testmedia.FixtureSpec
import dev.jordond.filmstrip.convention.testmedia.FixtureTransfer
import dev.jordond.filmstrip.convention.testmedia.testMedia
import dev.jordond.filmstrip.convention.usesHostFfmpeg
import dev.jordond.filmstrip.convention.usesHostFfmpegTools

plugins {
  id("filmstrip.library.jvm")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripEffects)
      api(projects.filmstripTransform)
      implementation(libs.kotlinx.io.core)
    }

    jvmTest.dependencies {
      implementation(projects.filmstripTest)
      implementation(projects.filmstripEffectsFilters)
    }
  }
}

val exportFixtures =
  testMedia(
    name = "export",
    specs =
      listOf(
        FixtureSpec("export_landscape", 640, 360, 30, 2.0, 48000, 2, bitrateKbps = 1_500),
        // A bed to mix under the others. Its tone is the one frequency no other fixture carries,
        // so a test measuring the mix can say which source a level belongs to.
        FixtureSpec("tone_bed_880", 320, 240, 30, 3.0, 48000, 2, bitrateKbps = 500, toneHz = 880),
        FixtureSpec("export_portrait", 480, 640, 30, 2.0, 48000, 2, bitrateKbps = 1_500, hue = 120),
        FixtureSpec("export_long", 1280, 720, 30, 12.0, 48000, 2, bitrateKbps = 6_000, hue = 240),
        FixtureSpec(
          "export_hdr",
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
          "export_hdr_hlg",
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

// Fails the build, naming why, before any test runs when this host's ffmpeg is missing, below the floor, lacks the
// bitstream filter a copy ends on or has no ten-bit encoder. It runs the same toolchain lookup the backend does, on the
// ffmpeg and ffprobe the tests get. Every test task that calls usesHostFfmpeg depends on it.
tasks.register<JavaExec>("checkFfmpegTestToolchain") {
  description = "Fails when this host's ffmpeg cannot run the ffmpeg tests, naming why."
  val test = kotlin.jvm().compilations.getByName("test")
  classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
  mainClass.set("dev.jordond.filmstrip.ffmpeg.support.ToolchainGateKt")
  javaLauncher.set(tasks.named<Test>("jvmTest").flatMap { it.javaLauncher })
  usesHostFfmpegTools()
}

tasks.named<Test>("jvmTest") {
  usesHostFfmpeg()
  systemProperty("filmstrip.fixtures", exportFixtures.directory.absolutePath)
  // ffmpeg-text-overlay: text overlays are drawn by the JVM the way a server draws them, with no display to reach.
  systemProperty("java.awt.headless", "true")
  dependsOn(exportFixtures.download)
}
