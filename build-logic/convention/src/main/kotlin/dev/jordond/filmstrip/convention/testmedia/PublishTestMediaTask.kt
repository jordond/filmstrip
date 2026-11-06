package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Pushes the clips a generate task just rolled to the bucket, then writes the manifest that pins them.
 *
 * Run by hand, never by a build, the same way `sample/media/upload.sh` is. Publishing is what makes
 * a fixture a fixed input, so it happens when somebody decides to change one.
 *
 * Objects are keyed by digest, so a clip whose bytes did not change republishes over the key it
 * already occupies and one that did change lands beside its predecessor. Nothing is deleted: an
 * older commit's manifest still names the object it was written against.
 *
 * Every fixture in [specs] has to be in [fixtures], and has to be bytes that were verified, before anything happens.
 * See [publishFixtures] for those checks, the build check and the order uploads and the manifest are written in.
 * [CheckWranglerTask] runs first, before anything is generated.
 *
 * The task declares no outputs, so Gradle runs it every time and a [noUpload] run never leaves a later upload up to
 * date.
 *
 * Only the fixtures in [specs] are uploaded and pinned. Any other file in [fixtures], such as a clip left behind by a
 * spec that was since removed, is skipped and named in the log. The generate task's own list, [generated], is not
 * named.
 */
abstract class PublishTestMediaTask
  @Inject
  constructor(
    private val exec: ExecOperations,
  ) : DefaultTask() {
    @get:Input
    abstract val specs: ListProperty<FixtureSpec>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: DirectoryProperty

    @get:Internal
    abstract val manifest: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generated: RegularFileProperty

    @get:Internal
    @get:Option(option = "no-upload", description = "Write the manifest and print the keys without uploading.")
    abstract val noUpload: Property<Boolean>

    @get:Internal
    @get:Option(option = "account", description = "Cloudflare account that owns the bucket.")
    abstract val account: Property<String>

    /**
     * Lets fixtures whose bytes changed on another ffmpeg build than the pinned one through, set by `-PrepinFixtures`.
     */
    @get:Internal
    abstract val repin: Property<Boolean>

    @TaskAction
    fun publish() {
      val upload = !noUpload.getOrElse(false)
      val directory = fixtures.get().asFile
      val declared = specs.get().map { it.fileName }.toSet()
      val generatedName = generated.orNull?.asFile?.name
      directory
        .listFiles { file -> file.isFile && file.name !in declared && file.name != generatedName }
        ?.sortedBy { it.name }
        ?.forEach { logger.lifecycle("ignoring ${it.name}, no declared fixture has that name") }

      val entries =
        publishFixtures(
          specs = specs.get(),
          directory = directory,
          manifest = manifest.get().asFile,
          generated = generated.orNull?.asFile,
          repin = repin.getOrElse(false),
          upload = if (upload) ::put else null,
        )

      if (!upload) {
        entries.forEach {
          logger.lifecycle("${it.fileName}  ${it.bytes} bytes  ${TestMediaManifest.urlFor(it)}")
        }
        return
      }
      logger.lifecycle("published ${entries.size} fixtures, ${manifest.get().asFile.name} written")
    }

    private fun put(
      entry: TestMediaEntry,
      file: File,
    ) {
      logger.lifecycle("publishing ${entry.fileName}")
      exec.exec {
        commandLine(
          WRANGLER,
          "r2",
          "object",
          "put",
          "${TestMediaManifest.BUCKET}/${TestMediaManifest.keyFor(entry)}",
          "--file",
          file.absolutePath,
          "--content-type",
          MEDIA_TYPES.getValue(entry.extension),
          "--remote",
        )
        account.orNull?.let { environment("CLOUDFLARE_ACCOUNT_ID", it) }
      }
    }
  }

/**
 * Checks, uploads and pins the fixtures in [specs], returning the entries written to [manifest].
 *
 * Every fixture has to be in [directory] and verified against [manifest] or [generated], see [requireEveryFixture] and
 * [requireVerifiedFixtures]. When the build [generated] names is not the one [manifest] was pinned with, any fixture
 * whose bytes moved fails the call naming both builds and those files, unless [repin] is set. A manifest with no build
 * header is an unknown build, so it never matches.
 *
 * Each fixture goes to [upload] in name order, and [manifest] is rewritten under the generated build only after every
 * upload returned, so a failed upload leaves the old pins in place. A null [upload] writes the manifest without
 * uploading anything.
 */
internal fun publishFixtures(
  specs: List<FixtureSpec>,
  directory: File,
  manifest: File,
  generated: File?,
  repin: Boolean,
  upload: ((TestMediaEntry, File) -> Unit)?,
): List<TestMediaEntry> {
  requireEveryFixture(specs, directory)
  requireVerifiedFixtures(specs, directory, manifest, generated)

  val files = specs.map { it.fileName }.sorted().map(directory::resolve)
  if (files.isEmpty()) throw GradleException("no fixtures declared for $directory, nothing to publish")

  val entries =
    files.map { TestMediaEntry(fileName = it.name, sha256 = TestMediaManifest.digestOf(it), bytes = it.length()) }
  val build = generated?.let(TestMediaManifest::readBuild)
  requireSameBuild(manifest, build, entries, repin)

  upload?.let { put -> entries.forEach { put(it, directory.resolve(it.fileName)) } }
  TestMediaManifest.write(manifest, entries, build)
  return entries
}

/**
 * Throws when [build] is not the build [pinned] names and any of [entries] holds bytes other than its pin, unless
 * [repin] is set. A fixture with no pin yet is new, not changed.
 */
internal fun requireSameBuild(
  pinned: File,
  build: String?,
  entries: List<TestMediaEntry>,
  repin: Boolean,
) {
  if (repin || !pinned.isFile) return
  val pinnedBuild = TestMediaManifest.readBuild(pinned)
  if (build != null && build == pinnedBuild) return

  val pins = TestMediaManifest.read(pinned).associate { it.fileName to it.sha256 }
  val changed = entries.filter { entry -> pins[entry.fileName]?.let { it != entry.sha256 } == true }
  if (changed.isEmpty()) return

  throw GradleException(
    "not publishing, ${changed.size} fixtures changed on ffmpeg ${build ?: "of an unknown build"} where " +
      "${pinned.name} was pinned on ffmpeg ${pinnedBuild ?: "of an unknown build"}: " +
      "${changed.joinToString(", ") { it.fileName }}. Another build encodes the same spec to other bytes. Publish " +
      "from the pinned build, or pass -PrepinFixtures to re-pin every changed fixture on this one.",
  )
}

/**
 * Throws naming [tool] when no directory on [path] holds an executable of that name.
 */
internal fun requireOnPath(
  tool: String,
  path: String,
) {
  val found =
    path
      .split(File.pathSeparatorChar)
      .filter { it.isNotEmpty() }
      .any { File(it, tool).canExecute() }
  if (!found) {
    throw GradleException(
      "$tool is not on the PATH. Install it, or pass --no-upload to only write the manifest.",
    )
  }
}

private val MEDIA_TYPES: Map<String, String> = mapOf("mp4" to "video/mp4")
