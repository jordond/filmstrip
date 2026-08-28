package dev.jordond.filmstrip.diagnostics

import dev.jordond.filmstrip.ComponentRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {
  @Test
  fun `reports to every registered listener`() {
    val first = mutableListOf<DiagnosticEvent>()
    val second = mutableListOf<DiagnosticEvent>()
    val registry =
      ComponentRegistry
        .Builder()
        .add(DiagnosticListener { first += it })
        .add(DiagnosticListener { second += it })
        .build()

    registry.report("ffmpeg", "toolchain", mapOf("version" to "7.1"))

    assertEquals(1, first.size)
    assertEquals(1, second.size)
    assertEquals("ffmpeg", first.single().source)
    assertEquals("toolchain", first.single().name)
    assertEquals("7.1", first.single().detail["version"])
  }

  @Test
  fun `a listener that throws does not stop the others`() {
    val seen = mutableListOf<DiagnosticEvent>()
    val registry =
      ComponentRegistry
        .Builder()
        .add(DiagnosticListener { error("no") })
        .add(DiagnosticListener { seen += it })
        .build()

    registry.report("core", "anything", emptyMap())

    assertEquals(1, seen.size)
  }

  @Test
  fun `backends are kept in registration order`() {
    val registry =
      ComponentRegistry
        .Builder()
        .add(BackendInfo("media3", "dev.jordond.filmstrip:filmstrip-transform-media3"))
        .add(BackendInfo("ffmpeg", "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"))
        .build()

    assertEquals(listOf("media3", "ffmpeg"), registry.backends.map { it.name })
  }

  @Test
  fun `a registry carries its backends and listeners into a new builder`() {
    val seen = mutableListOf<DiagnosticEvent>()
    val original =
      ComponentRegistry
        .Builder()
        .add(BackendInfo("ffmpeg", "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"))
        .add(DiagnosticListener { seen += it })
        .build()

    val copy = original.newBuilder().build()
    copy.report("ffmpeg", "invocation", mapOf("command" to "ffmpeg -i in.mp4 out.mp4"))

    assertEquals(listOf("ffmpeg"), copy.backends.map { it.name })
    assertTrue(seen.isNotEmpty())
  }
}
