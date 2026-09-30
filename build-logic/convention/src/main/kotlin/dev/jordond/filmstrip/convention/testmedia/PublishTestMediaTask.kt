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
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Writes the manifest for the clips a generate task just rolled, and pushes them to the bucket.
 *
 * Run by hand, never by a build, the same way `sample/media/upload.sh` is. Publishing is what makes
 * a fixture a fixed input, so it happens when somebody decides to change one.
 *
 * Objects are keyed by digest, so a clip whose bytes did not change republishes over the key it
 * already occupies and one that did change lands beside its predecessor. Nothing is deleted: an
 * older commit's manifest still names the object it was written against.
 *
 * Every fixture in [specs] has to be in [fixtures] before anything happens. A still the generate task skipped for a
 * missing tool, with no downloaded copy in its place, fails the task instead of losing its pinned manifest row.
 *
 * Each of those files also has to be bytes that were verified. Its SHA-256 must match its row in [manifest], or the
 * row the generate task wrote for it in [generated], so a truncated or hand edited file fails the task before the
 * manifest is rewritten or anything is uploaded.
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
    abstract val specs: ListProperty<Fixture>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: DirectoryProperty

    @get:OutputFile
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

    @TaskAction
    fun publish() {
      val directory = fixtures.get().asFile
      requireEveryFixture(specs.get(), directory, StillWriter(exec)::missingTool)
      requireVerifiedFixtures(specs.get(), directory, manifest.get().asFile, generated.orNull?.asFile)

      val declared = specs.get().map { it.fileName }.toSet()
      val generatedName = generated.orNull?.asFile?.name
      directory
        .listFiles { file -> file.isFile && file.name !in declared && file.name != generatedName }
        ?.sortedBy { it.name }
        ?.forEach { logger.lifecycle("ignoring ${it.name}, no declared fixture has that name") }

      val clips = declared.sorted().map(directory::resolve)
      if (clips.isEmpty()) throw GradleException("no fixtures declared for $directory, nothing to publish")

      val entries =
        clips.map {
          TestMediaEntry(fileName = it.name, sha256 = TestMediaManifest.digestOf(it), bytes = it.length())
        }

      TestMediaManifest.write(manifest.get().asFile, entries)

      if (noUpload.getOrElse(false)) {
        entries.forEach {
          logger.lifecycle("${it.fileName}  ${it.bytes} bytes  ${TestMediaManifest.urlFor(it)}")
        }
        return
      }

      entries.forEach { entry ->
        logger.lifecycle("publishing ${entry.fileName}")
        exec.exec {
          commandLine(
            "wrangler",
            "r2",
            "object",
            "put",
            "${TestMediaManifest.BUCKET}/${TestMediaManifest.keyFor(entry)}",
            "--file",
            directory.resolve(entry.fileName).absolutePath,
            "--content-type",
            MEDIA_TYPES.getValue(entry.extension),
            "--remote",
          )
          account.orNull?.let { environment("CLOUDFLARE_ACCOUNT_ID", it) }
        }
      }

      logger.lifecycle("published ${entries.size} fixtures, ${manifest.get().asFile.name} written")
    }
  }

private val MEDIA_TYPES: Map<String, String> =
  mapOf("mp4" to "video/mp4") + StillContainer.entries.associate { it.extension to it.mediaType }
