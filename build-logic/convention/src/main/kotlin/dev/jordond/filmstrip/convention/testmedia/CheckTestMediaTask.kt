package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Fails when any declared fixture has no file in the fixture directory.
 *
 * The publish task depends on this one and runs the same check itself, so nothing is uploaded and the manifest is not
 * rewritten while a clip or still is missing. The task has no outputs, so it runs every time and can be run on its own
 * without touching the bucket.
 */
abstract class CheckTestMediaTask
  @Inject
  constructor(
    private val exec: ExecOperations,
  ) : DefaultTask() {
    @get:Input
    abstract val specs: ListProperty<Fixture>

    @get:Internal
    abstract val fixtures: DirectoryProperty

    @TaskAction
    fun check() {
      requireEveryFixture(specs.get(), fixtures.get().asFile, StillWriter(exec)::missingTool)
      logger.lifecycle("all ${specs.get().size} fixtures present in ${fixtures.get().asFile}")
    }
  }

/**
 * Throws naming every fixture in [specs] whose file is not in [directory].
 *
 * A missing still whose container needs a tool this host lacks, as reported by [missingTool], also names that tool,
 * since the generate task skips such a still rather than failing.
 */
internal fun requireEveryFixture(
  specs: List<Fixture>,
  directory: File,
  missingTool: (FixtureStill) -> String?,
) {
  val missing =
    specs
      .filterNot { directory.resolve(it.fileName).isFile }
      .map { fixture ->
        val tool = (fixture as? FixtureStill)?.let(missingTool)
        if (tool == null) fixture.fileName else "${fixture.fileName} (skipped by generate, this host has no $tool)"
      }
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
 * [generated], the list a generate task writes with the digest of each file it verified. A still counts as verified
 * only through one of those two, since [StillWriter] checks it while writing it and deletes it when that check fails.
 * Anything else, such as a truncated download or a copy edited by hand, fails here before the manifest is rewritten or
 * anything is uploaded.
 */
internal fun requireVerifiedFixtures(
  specs: List<Fixture>,
  directory: File,
  pinned: File,
  generated: File?,
) {
  val pins = if (pinned.isFile) TestMediaManifest.read(pinned).associate { it.fileName to it.sha256 } else emptyMap()
  val fresh = generated?.takeIf { it.isFile }?.let(::generatedDigests).orEmpty()

  val unverified =
    specs.mapNotNull { fixture ->
      val digest = TestMediaManifest.digestOf(directory.resolve(fixture.fileName))
      val known = listOfNotNull(pins[fixture.fileName], fresh[fixture.name])
      when {
        digest in known -> null
        known.isEmpty() -> "${fixture.fileName} (not pinned and not generated)"
        else -> "${fixture.fileName} (sha256 $digest matches neither its pin nor a fresh generate)"
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
