enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    gradlePluginPortal()
    mavenCentral()
  }

  includeBuild("build-logic")
}

dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
  }
}

plugins {
  id("com.gradle.develocity") version "4.6.1"
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

develocity {
  buildScan {
    termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
    termsOfUseAgree.set("yes")

    publishing.onlyIf { context ->
      context.buildResult.failures.isNotEmpty() && !System.getenv("CI").isNullOrEmpty()
    }
  }
}

rootProject.name = "filmstrip-root"

include(
  ":filmstrip",
  ":filmstrip-core",
  ":filmstrip-effects",
  ":filmstrip-effects-filters",
  ":filmstrip-transform",
  ":filmstrip-transform-media3",
  ":filmstrip-transform-avfoundation",
  ":filmstrip-transform-webcodecs",
  ":filmstrip-transform-ffmpeg",
  ":filmstrip-transform-ffmpeg-bundled",
  ":filmstrip-player",
  ":filmstrip-compose",
  ":filmstrip-compose-ui",
  ":filmstrip-test",
  ":filmstrip-testing",
  ":filmstrip-io-filekit",
  ":filmstrip-io-filekit-compose",
  ":filmstrip-io-okio",
  ":filmstrip-io-kotlinx",
)

include(
  ":sample:common",
  ":sample:demo:shared",
  ":sample:demo:androidApp",
  ":sample:demo:desktopApp",
  ":sample:demo:webApp",
  ":sample:tryIt",
  ":sample:playground",
)

include(
  ":internal:ios-harness",
  ":internal:docs-samples",
  ":internal:dokka-plugin",
)
