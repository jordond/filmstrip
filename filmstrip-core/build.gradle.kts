import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

kotlin {
    android {
        namespace = "dev.jordond.filmstrip.filmstrip-core"
        compileSdk = 37
        minSdk = 23
        androidResources.enable = true
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }

    }
}

mavenPublishing {
    publishToMavenCentral()
    coordinates("dev.jordond.filmstrip", "filmstrip-core", "0.1.0")

    pom {
        name = "filmstrip"
        description = "Kotlin Multiplatform library"
        url = "https://github.com/jordond/filmstrip"

        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }

        developers {
            developer {
                id = "jordond"
                name = "Jordon de Hoog"
                email = "me@jordond.dev"
            }
        }

        scm {
            url = "https://github.com/jordond/filmstrip"
        }
    }

    if (project.hasProperty("signing.keyId")) signAllPublications()
}
