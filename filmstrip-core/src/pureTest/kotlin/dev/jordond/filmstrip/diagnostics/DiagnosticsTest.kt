package dev.jordond.filmstrip.diagnostics

import dev.jordond.filmstrip.ComponentRegistry
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DiagnosticsTest {
  @Test
  fun `reports to every registered listener`() {
    val first = mutableListOf<DiagnosticEvent>()
    val second = mutableListOf<DiagnosticEvent>()
    val registry =
      ComponentRegistry
        .Builder()
        .addDiagnosticListener(DiagnosticListener { first += it })
        .addDiagnosticListener(DiagnosticListener { second += it })
        .build()

    registry.report("ffmpeg", "toolchain", mapOf("version" to "7.1"))

    first.size shouldBe 1
    second.size shouldBe 1
    first.single().source shouldBe "ffmpeg"
    first.single().name shouldBe "toolchain"
    first.single().detail["version"] shouldBe "7.1"
  }

  @Test
  fun `a listener that throws does not stop the others`() {
    val seen = mutableListOf<DiagnosticEvent>()
    val registry =
      ComponentRegistry
        .Builder()
        .addDiagnosticListener(DiagnosticListener { error("no") })
        .addDiagnosticListener(DiagnosticListener { seen += it })
        .build()

    registry.report("core", "anything", emptyMap())

    seen.size shouldBe 1
  }

  @Test
  fun `backends are kept in registration order`() {
    val registry =
      ComponentRegistry
        .Builder()
        .addBackendInfo(BackendInfo("media3", "dev.jordond.filmstrip:filmstrip-transform-media3"))
        .addBackendInfo(BackendInfo("ffmpeg", "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"))
        .build()

    registry.backends.map { it.name } shouldBe listOf("media3", "ffmpeg")
  }

  @Test
  fun `a registry carries its backends and listeners into a new builder`() {
    val seen = mutableListOf<DiagnosticEvent>()
    val original =
      ComponentRegistry
        .Builder()
        .addBackendInfo(BackendInfo("ffmpeg", "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"))
        .addDiagnosticListener(DiagnosticListener { seen += it })
        .build()

    val copy = original.newBuilder().build()
    copy.report("ffmpeg", "invocation", mapOf("command" to "ffmpeg -i in.mp4 out.mp4"))

    copy.backends.map { it.name } shouldBe listOf("ffmpeg")
    seen.isNotEmpty().shouldBeTrue()
  }
}
