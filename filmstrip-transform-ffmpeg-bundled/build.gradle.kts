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

// These tests run the ffmpeg this module carries, which a FILMSTRIP_FFMPEG in the developer's shell would win over.
tasks.named<Test>("jvmTest") {
  doFirst {
    environment.remove("FILMSTRIP_FFMPEG")
    environment.remove("FILMSTRIP_FFPROBE")
  }
}
