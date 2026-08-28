plugins {
  id("filmstrip.library")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.io.core)
    }

    androidMain.dependencies {
      api(libs.kotlinx.coroutines.android)
      implementation(libs.androidx.startup)
    }
  }
}

// The version has to be readable at runtime: it is the first field a bug report asks for, and
// gradle.properties is not on the classpath.
val generateVersionFile by tasks.registering {
  val version = providers.gradleProperty("VERSION_NAME")
  val output = layout.buildDirectory.dir("generated/version")

  inputs.property("version", version)
  outputs.dir(output)

  doLast {
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
}

kotlin {
  sourceSets {
    commonMain {
      kotlin.srcDir(generateVersionFile)
    }
  }
}
