import dev.jordond.filmstrip.convention.testmedia.SharedFixtures
import dev.jordond.filmstrip.convention.testmedia.testMedia

plugins {
  id("filmstrip.library.web")
}

// The ffmpeg-written clips a browser test reads, served beside the test bundle as test resources.
val browserFixtures =
  testMedia(
    name = "browser",
    specs =
      listOf(
        SharedFixtures.ThreeAudio,
        SharedFixtures.ThreeAudioGapped,
        SharedFixtures.ThreeAudioMatroska,
        SharedFixtures.ThreeAudioTs,
      ),
  )

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripEffects)
      api(projects.filmstripTransform)
      implementation(npm("mediabunny", "1.55.1"))
    }

    commonTest {
      resources.srcDir(browserFixtures.directory)
      dependencies {
        implementation(projects.filmstripTest)
        implementation(projects.filmstripEffectsFilters)
      }
    }
  }
}

tasks.matching { it.name.endsWith("TestProcessResources") }.configureEach { dependsOn(browserFixtures.download) }
