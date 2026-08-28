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
