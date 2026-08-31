plugins {
  id("filmstrip.library.compose")
}

// The only artifact with Compose on the classpath.
kotlin {
  sourceSets {
    // Compose Multiplatform renders through Skia everywhere except Android, so the JVM, web and
    // Apple targets share one Skia-based implementation of platform-specific conversions.
    val skiaMain =
      create("skiaMain") {
        dependsOn(commonMain.get())
      }
    getByName("jvmMain").dependsOn(skiaMain)
    getByName("webMain").dependsOn(skiaMain)
    getByName("iosMain").dependsOn(skiaMain)

    commonMain.dependencies {
      api(projects.filmstripCore)
      api(projects.filmstripPlayer)

      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
    }

    // Skia's own binary, which a jvmTest that actually rasterises a bitmap needs on its runtime
    // classpath. commonMain has no such need: it only ever calls through the expect declaration.
    getByName("jvmTest").dependencies {
      implementation(compose.desktop.currentOs)
    }
  }
}
