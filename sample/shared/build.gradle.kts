import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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

  jvm()

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "Shared"
      isStatic = true
    }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    val jvmCommonMain by creating {
      dependsOn(commonMain.get())
    }
    androidMain.get().dependsOn(jvmCommonMain)
    jvmMain.get().dependsOn(jvmCommonMain)

    commonMain.dependencies {
      api(projects.filmstrip)
      implementation(libs.filekit.dialogs.compose)
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(libs.compose.adaptive)
      implementation(libs.compose.adaptive.layout)
      implementation(libs.navigation3.runtime)
      implementation(libs.navigation3.ui)
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
    }

    androidMain.dependencies {
      implementation(libs.androidx.activity.compose)
      implementation(libs.kotlinx.coroutines.android)
      implementation(libs.media3.exoplayer)
      implementation(libs.media3.ui)
    }

    wasmJsMain.dependencies {
      implementation(libs.kotlinx.browser)
    }

    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.swing)
    }
  }
}
