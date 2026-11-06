package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails when any declared fixture has no file in the fixture directory.
 *
 * The publish task depends on this one and runs the same check itself, so nothing is uploaded and the manifest is not
 * rewritten while a clip is missing. The task has no outputs, so it runs every time and can be run on its own without
 * touching the bucket.
 */
abstract class CheckTestMediaTask : DefaultTask() {
  @get:Input
  abstract val specs: ListProperty<FixtureSpec>

  @get:Internal
  abstract val fixtures: DirectoryProperty

  @TaskAction
  fun check() {
    requireEveryFixture(specs.get(), fixtures.get().asFile)
    logger.lifecycle("all ${specs.get().size} fixtures present in ${fixtures.get().asFile}")
  }
}

/**
 * Throws naming every fixture in [specs] whose file is not in [directory].
 */
internal fun requireEveryFixture(
  specs: List<FixtureSpec>,
  directory: File,
) {
  val missing = specs.filterNot { directory.resolve(it.fileName).isFile }.map { it.fileName }
  if (missing.isEmpty()) return

  throw GradleException(
    "not publishing, ${missing.size} declared fixtures are missing from $directory: ${missing.joinToString(", ")}. " +
      "Generate them on a host that can, or download the published copies, then publish again.",
  )
}

/**
 * Throws naming every fixture in [specs] whose file in [directory] holds bytes nothing has verified.
 *
 * A file passes when its SHA-256 matches its row in [pinned], the manifest from the last publish, or its row in
 * [generated], the list a generate task writes with the digest of each file it verified. Anything else, such as a
 * truncated download or a copy edited by hand, fails here before the manifest is rewritten or anything is uploaded.
 */
internal fun requireVerifiedFixtures(
  specs: List<FixtureSpec>,
  directory: File,
  pinned: File,
  generated: File?,
) {
  val pins = if (pinned.isFile) TestMediaManifest.read(pinned).associate { it.fileName to it.sha256 } else emptyMap()
  val fresh = generated?.takeIf { it.isFile }?.let(::generatedDigests).orEmpty()

  val unverified =
    specs.mapNotNull { fixture ->
      val digest = TestMediaManifest.digestOf(directory.resolve(fixture.fileName))
      val pin = pins[fixture.fileName]
      val generate = fresh[fixture.name]
      when {
        digest == pin || digest == generate -> null
        pin == null && generate == null -> "${fixture.fileName} (not pinned and not generated)"
        else -> "${fixture.fileName} (sha256 $digest, pinned ${pin ?: "none"}, generated ${generate ?: "none"})"
      }
    }
  if (unverified.isEmpty()) return

  throw GradleException(
    "not publishing, ${unverified.size} fixtures in $directory are not the bytes that were verified: " +
      "${unverified.joinToString(", ")}. Generate them again or download the published copies, then publish again.",
  )
}

private fun generatedDigests(file: File): Map<String, String> =
  file
    .readLines()
    .filterNot { it.isBlank() || it.startsWith("#") }
    .map { line -> line.split(" ").filter { it.isNotEmpty() } }
    .associate { fields -> fields.first() to fields.last() }
