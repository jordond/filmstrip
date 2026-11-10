import dev.jordond.filmstrip.convention.androidDeviceTests
import dev.jordond.filmstrip.convention.pureTests

plugins {
  id("filmstrip.library")
}

androidDeviceTests()
pureTests()

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
    // Everything that can open a file by path, which is every target but the browser.
    val filesMain = create("filesMain") { dependsOn(commonMain.get()) }
    val filesTest = create("filesTest") { dependsOn(commonTest.get()) }
    listOf("jvmMain", "androidMain", "appleMain").forEach { named(it) { dependsOn(filesMain) } }
    listOf("jvmTest", "appleTest").forEach { named(it) { dependsOn(filesTest) } }

    // Reads a still's header through the platform decoder, on the targets that measure one off the host. Android
    // decodes through BitmapFactory, which the device suite covers.
    // The primed audio file sits with the shared test fixtures, which depend on core, so core compiles its source in.
    commonTest { kotlin.srcDir("../filmstrip-test/src/primedAudio/kotlin") }

    val imageProbeTest = create("imageProbeTest") { dependsOn(commonTest.get()) }
    listOf("jvmTest", "macosArm64Test", "jsTest").forEach { named(it) { dependsOn(imageProbeTest) } }

    commonMain {
      kotlin.srcDir(generateVersionFile)

      dependencies {
        api(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.io.core)
        api(libs.compose.annotation)
      }
    }

    androidMain {
      dependencies {
        api(libs.kotlinx.coroutines.android)
        api(libs.media3.common)
        api(libs.media3.effect)
        implementation(libs.androidx.startup)
      }
    }

    named("androidDeviceTest") {
      kotlin.srcDir("src/commonTest/kotlin/dev/jordond/filmstrip/media/probe")
      kotlin.srcDir("../filmstrip-test/src/primedAudio/kotlin")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotest.assertions)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.androidx.test.runner)
      }
    }
  }
}
