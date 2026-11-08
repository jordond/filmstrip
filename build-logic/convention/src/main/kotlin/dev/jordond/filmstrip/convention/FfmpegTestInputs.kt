package dev.jordond.filmstrip.convention

import dev.jordond.filmstrip.convention.testmedia.FixtureBuildSource
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ProcessForkOptions
import java.io.File

/**
 * Makes the host's ffmpeg an input of this test task, so a new ffmpeg runs the tests again rather than taking them from
 * the build cache.
 *
 * The ffmpeg and ffprobe are the ones [hostFfmpegTools] finds. The ffmpeg's absolute path is the `ffmpegExecutable`
 * input and its build, as [FixtureBuildSource] reads it, is the `ffmpegBuild` input. The ffprobe's absolute path is the
 * `ffprobeExecutable` input. The test JVM gets both as the [FFMPEG_PROPERTY] and [FFPROBE_PROPERTY] system properties
 * and as `FILMSTRIP_FFMPEG` and `FILMSTRIP_FFPROBE`, so the backend under test and the tests read files with the same
 * binaries. The task depends on `checkFfmpegTestToolchain`, which fails naming why before any test runs when that
 * ffmpeg cannot run them.
 */
fun Test.usesHostFfmpeg() {
  val tools = project.hostFfmpegTools()
  inputs.property("ffmpegExecutable", tools.ffmpeg)
  inputs.property(
    "ffmpegBuild",
    project.providers.of(FixtureBuildSource::class.java) { parameters.executable.set(tools.ffmpeg) },
  )
  inputs.property("ffprobeExecutable", tools.ffprobe)
  jvmArgumentProviders.add(ToolProperty(FFMPEG_PROPERTY, tools.ffmpeg))
  jvmArgumentProviders.add(ToolProperty(FFPROBE_PROPERTY, tools.ffprobe))
  exportsHostFfmpegTools(tools)
  dependsOn(TOOLCHAIN_GATE)
}

/**
 * Hands this process the ffmpeg and ffprobe [hostFfmpegTools] finds, as `FILMSTRIP_FFMPEG` and `FILMSTRIP_FFPROBE`.
 *
 * The toolchain gate calls this so it checks the same binaries the tests from [usesHostFfmpeg] run with.
 */
fun JavaExec.usesHostFfmpegTools() {
  exportsHostFfmpegTools(project.hostFfmpegTools())
}

/**
 * The host's ffmpeg and ffprobe as absolute paths, each `none` when the host has none.
 *
 * @property ffmpeg What [FfmpegExecutableSource] finds.
 * @property ffprobe What [FfprobeExecutableSource] finds for that ffmpeg.
 */
internal class HostFfmpegTools(
  val ffmpeg: Provider<String>,
  val ffprobe: Provider<String>,
)

/**
 * The project's one [HostFfmpegTools], made on the first call, so every task in the project reads the same lookup.
 */
internal fun Project.hostFfmpegTools(): HostFfmpegTools {
  val extras = extensions.extraProperties
  if (extras.has(TOOLS_EXTRA)) return extras.get(TOOLS_EXTRA) as HostFfmpegTools
  val ffmpeg = providers.of(FfmpegExecutableSource::class.java) {}.orElse(NO_TOOL)
  val ffprobe =
    providers
      .of(FfprobeExecutableSource::class.java) { parameters.ffmpeg.set(ffmpeg) }
      .orElse(NO_TOOL)
  return HostFfmpegTools(ffmpeg, ffprobe).also { extras.set(TOOLS_EXTRA, it) }
}

private fun <T> T.exportsHostFfmpegTools(tools: HostFfmpegTools) where T : Task, T : ProcessForkOptions {
  val ffmpeg = tools.ffmpeg
  val ffprobe = tools.ffprobe
  doFirst {
    val options = this as ProcessForkOptions
    ffmpeg.get().takeIf { it != NO_TOOL }?.let { options.environment(FFMPEG_VARIABLE, it) }
    ffprobe.get().takeIf { it != NO_TOOL }?.let { options.environment(FFPROBE_VARIABLE, it) }
  }
}

/**
 * The system property a test JVM reads the host's ffmpeg from, set by [usesHostFfmpeg].
 */
internal const val FFMPEG_PROPERTY: String = "filmstrip.ffmpeg"

/**
 * The system property a test JVM reads the host's ffprobe from, set by [usesHostFfmpeg].
 */
internal const val FFPROBE_PROPERTY: String = "filmstrip.ffprobe"

/**
 * Finds ffmpeg the way the backend's toolchain lookup does when no path is configured: `FILMSTRIP_FFMPEG` when it names
 * a file, otherwise the first `ffmpeg` on the PATH. Absent when neither has one.
 */
internal abstract class FfmpegExecutableSource : ValueSource<String, ValueSourceParameters.None> {
  override fun obtain(): String? {
    System.getenv(FFMPEG_VARIABLE)?.takeIf { it.isNotBlank() }?.let { configured ->
      return File(configured).takeIf { it.isFile }?.absolutePath
    }
    return onPath("ffmpeg")
  }
}

/**
 * Finds ffprobe for the ffmpeg [FfmpegExecutableSource] found: `FILMSTRIP_FFPROBE` when it names a file, otherwise the
 * `ffprobe` beside that ffmpeg, otherwise the first `ffprobe` on the PATH. Absent when none of them has one.
 */
internal abstract class FfprobeExecutableSource : ValueSource<String, FfprobeExecutableSource.Parameters> {
  /**
   * Which ffmpeg the ffprobe goes with.
   *
   * @property ffmpeg The resolved ffmpeg's absolute path, or a value that names no file when there is none.
   */
  interface Parameters : ValueSourceParameters {
    val ffmpeg: Property<String>
  }

  override fun obtain(): String? {
    System.getenv(FFPROBE_VARIABLE)?.takeIf { it.isNotBlank() }?.let { configured ->
      return File(configured).takeIf { it.isFile }?.absolutePath
    }
    val ffmpeg = File(parameters.ffmpeg.get())
    if (ffmpeg.isFile) {
      ffmpeg.resolveSibling("ffprobe").takeIf { it.isFile }?.let { return it.absolutePath }
    }
    return onPath("ffprobe")
  }
}

private fun onPath(name: String): String? =
  System
    .getenv("PATH")
    .orEmpty()
    .split(File.pathSeparatorChar)
    .filter { it.isNotBlank() }
    .map { File(it, name) }
    .firstOrNull { it.isFile }
    ?.absolutePath

private class ToolProperty(
  @get:Input val name: String,
  @get:Input val executable: Provider<String>,
) : CommandLineArgumentProvider {
  override fun asArguments(): List<String> = listOf("-D$name=${executable.get()}")
}

private const val FFMPEG_VARIABLE = "FILMSTRIP_FFMPEG"

private const val FFPROBE_VARIABLE = "FILMSTRIP_FFPROBE"

private const val NO_TOOL = "none"

private const val TOOLS_EXTRA = "filmstrip.hostFfmpegTools"

private const val TOOLCHAIN_GATE = ":filmstrip-transform-ffmpeg:checkFfmpegTestToolchain"
