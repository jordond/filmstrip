package dev.jordond.filmstrip.convention.testmedia

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.util.Collections

internal fun List<String>.shouldContainRun(vararg run: String) {
  withClue("$this should hold ${run.toList()} side by side") {
    Collections.indexOfSubList(this, run.toList()) shouldNotBe -1
  }
}

internal fun spec(name: String): FixtureSpec = FixtureSpec(name, 320, 240, 30, 1.0, 48000, 2, bitrateKbps = 500)

internal fun File.writeFixture(
  spec: FixtureSpec,
  content: String = spec.name,
): TestMediaEntry {
  val file = resolve(spec.fileName).apply { writeText(content) }
  return TestMediaEntry(spec.fileName, TestMediaManifest.digestOf(file), file.length())
}

internal fun File.pin(
  entries: List<TestMediaEntry>,
  build: String?,
): File = resolve(TestMediaManifest.FILE_NAME).also { TestMediaManifest.write(it, entries, build) }

/**
 * Writes the list a generate task would for [entries], each row ending with its digest under a header naming [build].
 */
internal fun File.generatedList(
  entries: List<TestMediaEntry>,
  build: String,
): File =
  resolve("fixtures.txt").apply {
    writeText(
      TestMediaManifest.buildHeader(build) + "\n" +
        entries.joinToString("\n") { "${it.fileName.substringBefore('.')} 1 1 30 1 1.0 1 48000 2 1 ${it.sha256}" },
    )
  }
