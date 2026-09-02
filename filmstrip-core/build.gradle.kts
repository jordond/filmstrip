import dev.jordond.filmstrip.convention.androidDeviceTests

plugins {
  id("filmstrip.library")
  alias(libs.plugins.kotlin.serialization)
}

androidDeviceTests()

val generateVersionFile =
  tasks.register("generateVersionFile") {
    description = "Create a object containing the version for diagnostics"
    val version = providers.gradleProperty("VERSION_NAME")
    val output = layout.buildDirectory.dir("generated/version")

    inputs.property("version", version)
    outputs.dir(output)

    doLast {
      createVersionFile(output, version)
    }
  }

kotlin {
  sourceSets {
    commonMain {
      kotlin.srcDir(generateVersionFile)

      dependencies {
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.io.core)
        api(libs.compose.annotation)
      }
    }

    androidMain.dependencies {
      api(libs.kotlinx.coroutines.android)
      implementation(libs.androidx.startup)
    }

    named("androidDeviceTest") {
      kotlin.srcDir("src/commonTest/kotlin/dev/jordond/filmstrip/media/probe")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.androidx.test.runner)
      }
    }
  }
}

private fun createVersionFile(
  output: Provider<Directory>,
  version: Provider<String>,
) {
  val file = output.get().asFile.resolve("dev/jordond/filmstrip/FilmstripVersion.kt")
  file.parentFile.mkdirs()
  file.writeText(
    """
    package dev.jordond.filmstrip

    /**
     * The version of filmstrip this build was compiled from.
     */
    public object FilmstripVersion {
      /**
       * The published version, or the snapshot name on a build that is not a release.
       */
      public val name: String = "${version.get()}"
    }

    """.trimIndent(),
  )
}
