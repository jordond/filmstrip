package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI

/**
 * Fetches the fixtures a module's tests read, and refuses any whose bytes are not the published
 * ones.
 *
 * Downloads land in a digest-keyed cache outside the project, so a clip is fetched once per machine
 * however many modules read it and whatever branch or worktree is checked out.
 *
 * No ffmpeg is involved. A runner that only reads fixtures needs none installed, which is the whole
 * reason the clips are published rather than rolled per run.
 */
abstract class DownloadTestMediaTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    /**
     * Where the objects are served from, without the key.
     */
    @get:Internal
    abstract val baseUrl: Property<String>

    @TaskAction
    fun download() {
        val entries = TestMediaManifest.read(manifest.get().asFile)
        val outputDir = outputDirectory.get().asFile
        val cacheDir = cacheDirectory.get().asFile
        outputDir.mkdirs()
        cacheDir.mkdirs()

        entries.forEach { entry ->
            val cached = cacheDir.resolve("${entry.sha256}.mp4")
            if (!cached.isFile || TestMediaManifest.digestOf(cached) != entry.sha256) {
                fetch(entry, cached)
            }

            val target = outputDir.resolve(entry.fileName)
            if (target.isFile && TestMediaManifest.digestOf(target) == entry.sha256) return@forEach
            target.delete()
            cached.copyTo(target, overwrite = true)
        }
    }

    private fun fetch(
        entry: TestMediaEntry,
        target: File,
    ) {
        val url = "${baseUrl.get()}/${TestMediaManifest.keyFor(entry.sha256)}"
        val partial = File("${target.path}.part")
        partial.delete()

        logger.lifecycle("fetching ${entry.fileName}")
        runCatching {
            URI(url).toURL().openStream().use { stream ->
                partial.outputStream().use { stream.copyTo(it) }
            }
        }.onFailure {
            partial.delete()
            throw GradleException("could not fetch ${entry.fileName} from $url", it)
        }

        // Hashing what arrived is what makes a replaced object a build failure rather than a test
        // failure somewhere else entirely.
        val digest = TestMediaManifest.digestOf(partial)
        if (digest != entry.sha256) {
            partial.delete()
            throw GradleException(
                "${entry.fileName} at $url hashes to $digest, and ${TestMediaManifest.FILE_NAME} says " +
                    "${entry.sha256}. Either the object was replaced or the manifest is stale.",
            )
        }

        partial.renameTo(target)
    }
}
