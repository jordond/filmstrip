import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// A Dokka plugin that leaves @InternalFilmstripApi declarations out of the API reference.
plugins {
  id("filmstrip.internal")
  alias(libs.plugins.multiplatform)
}

kotlin {
  jvmToolchain(
    libs.versions.jvmTarget
      .get()
      .toInt(),
  )

  jvm {
    compilerOptions {
      // Dokka runs the plugin on its own Kotlin stdlib, which is older than the one this build compiles with.
      apiVersion.set(KotlinVersion.KOTLIN_2_1)
      languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
  }

  sourceSets {
    jvmMain.dependencies {
      compileOnly("org.jetbrains.dokka:dokka-core:${libs.versions.dokka.get()}")
      compileOnly("org.jetbrains.dokka:dokka-base:${libs.versions.dokka.get()}")
    }
  }
}
