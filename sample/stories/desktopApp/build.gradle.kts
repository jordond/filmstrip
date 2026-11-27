import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("filmstrip.internal")
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(
    libs.versions.jvmTarget
      .get()
      .toInt(),
  )

  jvm()

  sourceSets {
    jvmMain.dependencies {
      implementation(projects.sample.stories.shared)
      implementation(compose.desktop.currentOs)
    }
  }
}

compose.desktop {
  application {
    mainClass = "dev.jordond.filmstrip.sample.stories.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "filmstrip-stories"
      packageVersion =
        libs.versions.sample.version
          .get()
    }
  }
}
