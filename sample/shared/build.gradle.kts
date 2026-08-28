plugins {
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.android.multiplatform.library)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  android {
    namespace = "dev.jordond.filmstrip.sample.shared"
    compileSdk = libs.versions.sdk.compile.get().toInt()
    minSdk = libs.versions.sdk.min.get().toInt()
  }

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "Shared"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(projects.filmstrip)
      implementation(libs.filekit.dialogs.compose)
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
    }

    androidMain.dependencies {
      implementation(libs.kotlinx.coroutines.android)
      implementation(libs.media3.exoplayer)
      implementation(libs.media3.ui)
    }
  }
}
