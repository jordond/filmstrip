import dev.jordond.filmstrip.convention.testmedia.FixturePatch
import dev.jordond.filmstrip.convention.testmedia.FixtureSpec
import dev.jordond.filmstrip.convention.testmedia.FixtureTransfer
import dev.jordond.filmstrip.convention.testmedia.testMedia

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
  testMedia(
    name = "export",
    specs =
      listOf(
        FixtureSpec("export_landscape", 640, 360, 30, 2.0, 48000, 2, bitrateKbps = 1_500),
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

tasks.named<Test>("jvmTest") {
  systemProperty("filmstrip.fixtures", exportFixtures.directory.absolutePath)
  dependsOn(exportFixtures.download)
}
