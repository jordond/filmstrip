import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(libs.versions.jvmTarget.get().toInt())

  jvm()

  sourceSets {
    jvmMain.dependencies {
      implementation(projects.sample.shared)
      implementation(compose.desktop.currentOs)
    }
  }
}

compose.desktop {
  application {
    mainClass = "dev.jordond.filmstrip.sample.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "filmstrip"
      packageVersion = "1.0.0"
    }
  }
}
