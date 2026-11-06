package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Reads the build of the host's ffmpeg, as [TestMediaManifest.buildOf] records it, from `ffmpeg -version`, one-frame
 * probe encodes and the JVM's `os.arch`.
 *
 * Gradle runs this each time it resolves the value and does not keep it in the configuration cache, so a task input fed
 * from here sees the host as it is now. Nothing runs unless such a task is in the build.
 */
abstract class FixtureBuildSource
  @Inject
  constructor(
    private val exec: ExecOperations,
  ) : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String =
      TestMediaManifest.buildOf(
        versionLine = exec.capture(listOf("ffmpeg", "-version")).lineSequence().first(),
        x264Stream = exec.capture(encoderProbeArgs("libx264", "yuv420p", "h264")),
        x265Stream = exec.capture(encoderProbeArgs("libx265", "yuv420p10le", "hevc")),
        architecture = System.getProperty("os.arch"),
      )
  }
