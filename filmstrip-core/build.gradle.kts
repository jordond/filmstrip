import dev.jordond.filmstrip.convention.androidDeviceTests

plugins {
  id("filmstrip.library")
  alias(libs.plugins.kotlin.serialization)
}

androidDeviceTests()

// The action body is inlined rather than calling a function declared in this script, since a
// reference to the script object is one of the things the configuration cache cannot serialize.
val generateVersionFile =
  tasks.register("generateVersionFile") {
    description = "Create a object containing the version for diagnostics"
    val version = providers.gradleProperty("VERSION_NAME")
    val output = layout.buildDirectory.dir("generated/version")

    inputs.property("version", version)
    outputs.dir(output)

    doLast {
      val name = version.get()
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
          public val name: String = "$name"
        }

        """.trimIndent(),
      )
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
