package dev.jordond.filmstrip.convention.testmedia

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.api.GradleException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CheckTestMediaTest {
  private val directory = Files.createTempDirectory("fixtures").toFile()

  private val specs = listOf("first", "second", "third").map(::spec)

  @Test
  fun `every fixture present passes`() {
    specs.forEach { directory.writeFixture(it) }

    requireEveryFixture(specs, directory)
  }

  @Test
  fun `a missing fixture is named`() {
    specs.filter { it.name != "second" }.forEach { directory.writeFixture(it) }

    val error = assertFailsWith<GradleException> { requireEveryFixture(specs, directory) }

    error.message shouldContain "second.mp4"
    error.message shouldNotContain "first.mp4"
  }

  @Test
  fun `a fixture matching its pin passes`() {
    val pinned = directory.pin(specs.map { directory.writeFixture(it) }, BUILD)

    requireVerifiedFixtures(specs, directory, pinned, generated = null)
  }

  @Test
  fun `a fixture matching its generated row passes`() {
    val generated = directory.generatedList(specs.map { directory.writeFixture(it) }, BUILD)

    requireVerifiedFixtures(specs, directory, directory.resolve("absent.txt"), generated)
  }

  @Test
  fun `a fixture matching neither is named with every digest`() {
    val entries = specs.map { directory.writeFixture(it) }
    val pinned = directory.pin(entries, BUILD)
    val generated = directory.generatedList(entries, BUILD)
    val edited = directory.writeFixture(specs[1], "edited by hand")

    val error =
      assertFailsWith<GradleException> { requireVerifiedFixtures(specs, directory, pinned, generated) }

    error.message shouldContain
      "second.mp4 (sha256 ${edited.sha256}, pinned ${entries[1].sha256}, generated ${entries[1].sha256})"
    error.message shouldNotContain "first.mp4"
    error.message shouldNotContain "third.mp4"
  }

  @Test
  fun `a fixture neither pinned nor generated is named`() {
    specs.forEach { directory.writeFixture(it) }

    val error =
      assertFailsWith<GradleException> {
        requireVerifiedFixtures(specs, directory, directory.resolve("absent.txt"), generated = null)
      }

    error.message shouldContain "first.mp4 (not pinned and not generated)"
  }

  private companion object {
    const val BUILD = "8.0.1"
  }
}
