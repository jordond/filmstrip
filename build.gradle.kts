plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    alias(libs.plugins.compose).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.maven.publish).apply(false)
    alias(libs.plugins.poko).apply(false)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binaryCompatibility)
    alias(libs.plugins.kotlinx.kover)
}

apiValidation {
    nonPublicMarkers += "dev.jordond.filmstrip.InternalFilmstripApi"
}