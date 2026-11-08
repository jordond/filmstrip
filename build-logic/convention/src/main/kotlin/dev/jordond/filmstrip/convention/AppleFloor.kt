package dev.jordond.filmstrip.convention

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

/**
 * The oldest iOS filmstrip runs on, the first with AVFoundation's sample cursor.
 *
 * Kotlin/Native builds for iOS 15 unless told otherwise, so every iOS target in the build is given this one.
 */
internal const val IOS_FLOOR = "16.0"

/**
 * Builds every iOS target of this project for [IOS_FLOOR], once the multiplatform plugin is applied.
 *
 * The floor is a target-level compiler option, so it reaches each compilation's klib and every binary linked from
 * one, frameworks and test executables alike.
 */
internal fun Project.configureIosFloor() {
  pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
      targets.withType(KotlinNativeTarget::class.java).configureEach {
        if (konanTarget.family == Family.IOS) {
          compilerOptions {
            freeCompilerArgs.add("-Xoverride-konan-properties=osVersionMin.${konanTarget.name}=$IOS_FLOOR")
          }
        }
      }
    }
  }
}
