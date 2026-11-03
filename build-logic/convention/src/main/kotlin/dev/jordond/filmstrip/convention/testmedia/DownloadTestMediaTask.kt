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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Fetches the fixtures a module's tests read, and refuses any whose bytes are not the published
 * ones.
 *
 * Downloads land in a digest-keyed cache outside the project, so a clip is fetched once per machine
 * however many modules read it and whatever branch or worktree is checked out.
 *
 * Several tasks, in one build or in separate builds, can fetch the same clip at once. Each writes to
 * a file of its own and renames it into place, so the cache only ever holds whole, verified clips. A
 * clip already cached with the right digest is used as is, even when this task's own fetch failed.
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
    Files.createDirectories(outputDir.toPath())
    Files.createDirectories(cacheDir.toPath())

    entries.forEach { entry ->
      val cached = cacheDir.resolve("${entry.sha256}.${entry.extension}")
      if (!cached.holds(entry)) {
        runCatching { fetch(entry, cached) }.onFailure { error ->
          if (!cached.holds(entry)) throw error
          logger.info("fetching ${entry.fileName} failed, using the copy another build cached", error)
        }
      }

      val target = outputDir.resolve(entry.fileName)
      if (target.holds(entry)) return@forEach
      target.replaceWith { partial -> Files.copy(cached.toPath(), partial) }
    }
  }

  private fun fetch(
    entry: TestMediaEntry,
    target: File,
  ) {
    val url = "${baseUrl.get()}/${TestMediaManifest.keyFor(entry)}"

    logger.lifecycle("fetching ${entry.fileName}")
    target.replaceWith { partial ->
      downloadWithRetry(entry.fileName, url, partial)

      // Hashing what arrived is what makes a replaced object a build failure rather than a test
      // failure somewhere else entirely.
      val digest = TestMediaManifest.digestOf(partial.toFile())
      if (digest != entry.sha256) {
        throw GradleException(
          "${entry.fileName} at $url hashes to $digest, and ${TestMediaManifest.FILE_NAME} says " +
            "${entry.sha256}. Either the object was replaced or the manifest is stale.",
        )
      }
    }
  }

  /**
   * Copies [url] into [partial], trying again after a short wait when the network fails or the server answers 5xx or
   * 429. Any other 4xx fails at once, as does the last failed attempt.
   */
  private fun downloadWithRetry(
    fileName: String,
    url: String,
    partial: Path,
  ) {
    for (attempt in 1..FETCH_ATTEMPTS) {
      val failure =
        try {
          download(url, partial)
          return
        } catch (error: TransientFetchFailure) {
          error
        } catch (error: IOException) {
          error
        }

      if (attempt == FETCH_ATTEMPTS) {
        throw GradleException("could not fetch $fileName from $url after $FETCH_ATTEMPTS attempts", failure)
      }
      val backoff = RETRY_BACKOFF_MILLIS shl (attempt - 1)
      logger.lifecycle(
        "fetching $fileName failed on attempt $attempt of $FETCH_ATTEMPTS (${failure.message}), retrying in ${backoff}ms",
      )
      Thread.sleep(backoff)
    }
  }

  private fun download(
    url: String,
    partial: Path,
  ) {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
    connection.readTimeout = READ_TIMEOUT_MILLIS
    try {
      val status = connection.responseCode
      when {
        status in 200..299 -> {
          connection.inputStream.use { stream -> Files.copy(stream, partial, StandardCopyOption.REPLACE_EXISTING) }
        }
        status >= 500 || status == 429 -> {
          throw TransientFetchFailure("HTTP $status")
        }
        else -> {
          throw GradleException("could not fetch $url, the server answered HTTP $status")
        }
      }
    } finally {
      connection.disconnect()
    }
  }

  private class TransientFetchFailure(
    message: String,
  ) : Exception(message)

  private companion object {
    const val FETCH_ATTEMPTS = 4
    const val RETRY_BACKOFF_MILLIS = 1_000L
    const val CONNECT_TIMEOUT_MILLIS = 15_000
    const val READ_TIMEOUT_MILLIS = 60_000
  }
}

private fun File.holds(entry: TestMediaEntry): Boolean = isFile && TestMediaManifest.digestOf(this) == entry.sha256

/**
 * Has [write] fill a new file beside this one, then renames it over this one.
 *
 * The new file's name is unique to the call, so a concurrent writer never truncates or moves it. The
 * rename swaps the file in one step, so a reader sees either the old file or the whole new one.
 */
private fun File.replaceWith(write: (Path) -> Unit) {
  val partial = toPath().resolveSibling("$name.${UUID.randomUUID()}.part")
  try {
    write(partial)
    Files.move(partial, toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } finally {
    Files.deleteIfExists(partial)
  }
}
