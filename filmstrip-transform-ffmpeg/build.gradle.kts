import dev.jordond.filmstrip.convention.testmedia.FixtureSpec
import dev.jordond.filmstrip.convention.testmedia.FixtureTransfer
import dev.jordond.filmstrip.convention.testmedia.GenerateTestMediaTask

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
  }
}

val exportFixtures =
  tasks.register<GenerateTestMediaTask>("generateExportFixtures") {
    group = "verification"
    description = "Generates the clips the ffmpeg export test renders."
    specs.set(
      listOf(
        FixtureSpec("export_landscape", 640, 360, 30, 2.0, 48000, 2, bitrateKbps = 1_500),
        FixtureSpec("export_portrait", 480, 640, 30, 2.0, 48000, 2, bitrateKbps = 1_500, hue = 120),
        FixtureSpec("export_long", 1280, 720, 30, 12.0, 48000, 2, bitrateKbps = 6_000, hue = 240),
        FixtureSpec("export_hdr", 1280, 720, 30, 2.0, 48000, 2, bitrateKbps = 6_000, transfer = FixtureTransfer.Pq),
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
        ),
      ),
    )
    outputDirectory.set(layout.buildDirectory.dir("test-fixtures"))
    manifest.set(layout.buildDirectory.file("test-fixtures/fixtures.txt"))
  }

val hasFfmpeg =
  providers.environmentVariable("PATH").orElse("").map { path ->
    path.split(File.pathSeparatorChar).any { entry -> File(entry, "ffmpeg").canExecute() }
  }

tasks.named<Test>("jvmTest") {
  val output =
    layout.buildDirectory
      .dir("test-fixtures")
      .get()
      .asFile
  systemProperty("filmstrip.fixtures", output.absolutePath)

  if (hasFfmpeg.get()) {
    dependsOn(exportFixtures)
  }
}
