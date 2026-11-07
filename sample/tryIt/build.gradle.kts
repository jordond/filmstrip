import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("filmstrip.internal")
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(
    libs.versions.jvmTarget
      .get()
      .toInt(),
  )

  jvm()

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName = "try"
    browser {
      commonWebpackConfig {
        outputFileName = "try.js"
      }
      testTask {
        useKarma {
          useChromeHeadless()
        }
      }
    }
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.sample.common)
      implementation(projects.filmstripCompose)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.ui)
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotest.assertions)
      implementation(libs.kotlinx.coroutines.test)
    }

    wasmJsMain.dependencies {
      implementation(libs.compose.ui)
      implementation(libs.kotlinx.browser)
    }
  }
}

// A page cannot read a file off disk, so the fixture both sides of the protocol test against reaches the wasm test as
// a generated constant.
val protocolFixture = layout.projectDirectory.file("protocol.fixture.json")
val generatedFixture = layout.buildDirectory.dir("generated/protocolFixture")
val generateProtocolFixture =
  tasks.register("generateProtocolFixture") {
    inputs.file(protocolFixture).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedFixture)
    val fixture = protocolFixture.asFile
    val target = generatedFixture.get().file("ProtocolFixture.kt").asFile
    doLast {
      val json = fixture.readText().replace("$", "\${'$'}")
      target.parentFile.mkdirs()
      target.writeText(
        "package dev.jordond.filmstrip.sample.tryit\n\n" +
          "internal const val PROTOCOL_FIXTURE: String = \"\"\"$json\"\"\"\n",
      )
    }
  }

kotlin.sourceSets.named("wasmJsTest") {
  kotlin.srcDir(generateProtocolFixture)
}

// LandingSnippetTest reads the landing page's snippet, which Gradle can't otherwise see is an input of it.
val landingSnippet =
  rootProject.layout.projectDirectory.file(
    "internal/docs-samples/src/commonMain/kotlin/dev/jordond/filmstrip/docs/landing/Landing.kt",
  )
tasks.named<Test>("jvmTest") {
  inputs.file(landingSnippet).withPathSensitivity(PathSensitivity.RELATIVE)
  systemProperty("landing.snippet", landingSnippet.asFile.path)
}
