package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
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
 */
abstract class PublishTestMediaTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val fixtures: DirectoryProperty

        @get:OutputFile
        abstract val manifest: RegularFileProperty

        @get:Option(option = "dry-run", description = "Write the manifest and print the keys without uploading.")
        abstract val dryRun: Property<Boolean>

        @get:Option(option = "account", description = "Cloudflare account that owns the bucket.")
        abstract val account: Property<String>

        @TaskAction
        fun publish() {
            val directory = fixtures.get().asFile
            val clips =
                directory
                    .listFiles { file -> file.isFile && file.extension == "mp4" }
                    ?.sortedBy { it.name }
                    .orEmpty()

            if (clips.isEmpty()) throw GradleException("no clips in $directory, run the generate task first")

            val entries =
                clips.map {
                    TestMediaEntry(fileName = it.name, sha256 = TestMediaManifest.digestOf(it), bytes = it.length())
                }

            TestMediaManifest.write(manifest.get().asFile, entries)

            if (dryRun.getOrElse(false)) {
                entries.forEach {
                    logger.lifecycle("${it.fileName}  ${it.bytes} bytes  ${TestMediaManifest.urlFor(it.sha256)}")
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
                        "${TestMediaManifest.BUCKET}/${TestMediaManifest.keyFor(entry.sha256)}",
                        "--file",
                        directory.resolve(entry.fileName).absolutePath,
                        "--content-type",
                        "video/mp4",
                        "--remote",
                    )
                    account.orNull?.let { environment("CLOUDFLARE_ACCOUNT_ID", it) }
                }
            }

            logger.lifecycle("published ${entries.size} clips, ${manifest.get().asFile.name} written")
        }
    }
