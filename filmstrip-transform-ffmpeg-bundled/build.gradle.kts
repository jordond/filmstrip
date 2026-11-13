plugins {
  id("filmstrip.library.jvm")
}

// Every platform bytedeco builds ffmpeg for. BundledBinaries names the same five, and its test fails when it names
// one that is not here.
val platforms = listOf("linux-arm64", "linux-x86_64", "macosx-arm64", "macosx-x86_64", "windows-x86_64")

kotlin {
  sourceSets {
    jvmMain.dependencies {
      api(projects.filmstripTransformFfmpeg)
      implementation(libs.javacpp)
      platforms.forEach { platform ->
        runtimeOnly(project.dependencies.variantOf(libs.bytedeco.ffmpeg) { classifier(platform) })
      }
    }

    jvmTest.dependencies {
      implementation(projects.filmstripPlayer)
    }
  }
}

// bytedeco's GPL build for the machine running the tests, or null where bytedeco has none.
val gplPlatform: String? =
  run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch == "aarch64" || arch == "arm64"
    when {
      os.startsWith("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
      os.startsWith("linux") -> if (arm) "linux-arm64" else "linux-x86_64"
      os.startsWith("windows") && !arm -> "windows-x86_64"
      else -> null
    }
  }

val gplFfmpeg: Configuration by configurations.creating {
  isCanBeConsumed = false
}

dependencies {
  gplPlatform?.let { gplFfmpeg(variantOf(libs.bytedeco.ffmpeg) { classifier("$it-gpl") }) }
}

val jvmTest = tasks.named<Test>("jvmTest")

// The Gpl classes need the GPL jar on the classpath, which would change what every other class here measures, so
// they run in a task of their own after jvmTest.
val jvmGplTest =
  tasks.register<Test>("jvmGplTest") {
    description = "Runs the tests that need bytedeco's GPL ffmpeg on the classpath."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = jvmTest.get().testClassesDirs
    classpath = jvmTest.get().classpath + gplFfmpeg
    filter.includeTestsMatching("*.Gpl*")
    enabled = gplPlatform != null
  }

jvmTest.configure {
  filter.excludeTestsMatching("*.Gpl*")
  finalizedBy(jvmGplTest)
}

// These tests run the ffmpeg this module carries, which a FILMSTRIP_FFMPEG in the developer's shell would win over.
tasks.withType<Test>().configureEach {
  doFirst {
    environment.remove("FILMSTRIP_FFMPEG")
    environment.remove("FILMSTRIP_FFPROBE")
  }
}
