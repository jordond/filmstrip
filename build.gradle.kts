plugins {
  id("filmstrip.root")
  alias(libs.plugins.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.multiplatform.library) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.poko) apply false
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.publish) apply false
  alias(libs.plugins.dokka)
  alias(libs.plugins.dependencies)
  alias(libs.plugins.kotlinx.kover)
}

dependencies {
  subprojects
    .filter { it.name.startsWith("filmstrip") }
    .forEach { dokka(project(it.path)) }
}

dokka {
  dokkaPublications.html {
    outputDirectory.set(rootDir.resolve("dokka"))
  }
}
