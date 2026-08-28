package dev.jordond.filmstrip.convention

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * Declares the targets, compiler settings and Android config shared by every published module.
 *
 * Targets are declared eagerly rather than through a lazily-read DSL extension: KMP builds its
 * source sets at target-declaration time, so anything read in `afterEvaluate` would be too late.
 *
 * @param android The Android target, on `com.android.kotlin.multiplatform.library`. Off for a
 *   module with no Android engine of its own.
 * @param apple The iOS targets, and `macosArm64` when [macOs] is also set. Off for a module with no
 *   Apple engine of its own.
 * @param macOs `macosArm64` alongside the iOS targets. Off for Compose bindings, which have no
 *   desktop surface in v1.
 * @param web `js` and `wasmJs`, both with a browser environment. They share a `webMain` source set
 *   for everything pure Kotlin, and split only the raw JS interop, so the two targets cost one
 *   implementation. Compose Multiplatform ships both, and its compatibility mode links the js
 *   variant for older browsers, which is why a js-only consumer cannot take the wasmJs artifact.
 * @param jvm The JVM desktop target. It produces no framework, so it costs the Apple surface
 *   nothing, and it is what the parity harness and the ffmpeg export backend run on.
 */
internal fun Project.configureFilmstripLibrary(
  android: Boolean = true,
  apple: Boolean = true,
  macOs: Boolean,
  web: Boolean,
  jvm: Boolean,
) {
  extensions.configure<KotlinMultiplatformExtension> {
    configureShared(this@configureFilmstripLibrary)

    if (android) configureAndroid(this@configureFilmstripLibrary)
    if (apple) configureApple(this@configureFilmstripLibrary, macOs)
    if (web) configureWeb()
    if (jvm) jvm()
  }

  configurePublishing()
}

/**
 * Declares a published module that only targets the JVM.
 *
 * For a backend whose implementation is a desktop toolchain rather than a platform framework.
 * Everything but the target list matches [configureFilmstripLibrary].
 */
internal fun Project.configureFilmstripJvmLibrary() {
  extensions.configure<KotlinMultiplatformExtension> {
    configureShared(this@configureFilmstripJvmLibrary)
    jvm()
  }

  configurePublishing()
}

private fun KotlinMultiplatformExtension.configureShared(project: Project) {
  explicitApi()
  jvmToolchain(project.intVersion("jvmTarget"))
  applyDefaultHierarchyTemplate()

  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
    optIn.add("dev.jordond.filmstrip.InternalFilmstripApi")
    optIn.add("dev.jordond.filmstrip.ExperimentalFilmstripApi")
  }

  sourceSets.commonTest.dependencies {
    implementation(kotlin("test"))
    implementation(project.library("kotlinx-coroutines-test"))
    implementation(project.library("kotest-assertions"))
    implementation(project.library("turbine"))
  }
}

/**
 * Adds the `androidDeviceTest` source set and its instrumentation runner.
 *
 * Call it from any module whose tests touch MediaCodec, which cannot run on the JVM.
 */
fun Project.androidDeviceTests() {
  extensions.configure<KotlinMultiplatformExtension> {
    androidLibraryTarget()?.withDeviceTest {
      instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
  }
}

private fun KotlinMultiplatformExtension.androidLibraryTarget(): KotlinMultiplatformAndroidLibraryTarget? =
  (this as ExtensionAware).extensions.findByType(KotlinMultiplatformAndroidLibraryTarget::class.java)

private fun KotlinMultiplatformExtension.configureAndroid(project: Project) {
  androidLibraryTarget()?.apply {
    namespace = project.androidNamespace()
    compileSdk = project.intVersion("sdk-compile")
    minSdk = project.intVersion("sdk-min")

    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(project.version("jvmTarget")))

    withHostTest {
      isIncludeAndroidResources = true
    }
  }

  sourceSets.named("androidHostTest").configure {
    dependencies {
      implementation(project.library("mockk-android"))
      implementation(project.library("mockk-agent"))
    }
  }
}

/**
 * The browser targets. `nodejs()` is left off: every binding filmstrip needs is a DOM or WebCodecs
 * global that Node does not have.
 *
 * The js target emits ES modules to match wasmJs, so both browser halves pull npm packages the same
 * way: a static `@JsModule` external with no `@JsNonModule` UMD fallback behind it. That is what
 * lets the bundler tree-shake a package, and what stops one being instantiated twice.
 */
@OptIn(ExperimentalWasmDsl::class)
private fun KotlinMultiplatformExtension.configureWeb() {
  js {
    useEsModules()
    browser()
  }
  wasmJs {
    browser()
  }
}

private fun KotlinMultiplatformExtension.configureApple(
  project: Project,
  macOs: Boolean,
) {
  val frameworkName = project.frameworkBaseName()

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = frameworkName
      isStatic = true
    }
  }

  if (macOs) {
    macosArm64()
  }

  // TODO: Determine if this is needed still
  // https://kotlinlang.org/docs/native-objc-interop.html#export-of-kdoc-comments
  targets.withType(KotlinNativeTarget::class.java).configureEach {
    compilations["main"].compileTaskProvider.configure {
      compilerOptions {
        freeCompilerArgs.add("-Xexport-kdoc")
      }
    }
  }
}

/**
 * `:filmstrip-core` -> `dev.jordond.filmstrip.core`.
 */
private fun Project.androidNamespace(): String {
  val trimmed = name.removePrefix("filmstrip-").removePrefix("filmstrip")
  return if (trimmed.isBlank()) version("group") else "${version("group")}.${trimmed.replace("-", ".")}"
}

/**
 * `:filmstrip-core` -> `FilmstripCore`.
 */
private fun Project.frameworkBaseName(): String =
  name
    .split("-", "_", ".")
    .filter { it.isNotBlank() }
    .joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
