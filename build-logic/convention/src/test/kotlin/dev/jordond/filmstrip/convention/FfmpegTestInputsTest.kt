package dev.jordond.filmstrip.convention

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Builds a project whose test task calls [usesHostFfmpeg] and whose gate calls [usesHostFfmpegTools], against stub
 * ffmpegs that only answer the probes that read the build and stub ffprobes, and prints what the tasks declare.
 */
class FfmpegTestInputsTest {
  private val project = Files.createTempDirectory("ffmpeg-inputs-project").toFile()

  @BeforeTest
  fun writeProject() {
    project.resolve("settings.gradle").writeText(
      "rootProject.name = 'stub'\ninclude 'filmstrip-transform-ffmpeg'\n",
    )
    project.resolve("filmstrip-transform-ffmpeg").mkdirs()
    project.resolve("filmstrip-transform-ffmpeg/build.gradle").writeText(
      "tasks.register('checkFfmpegTestToolchain')\n",
    )
    project.resolve("build.gradle").writeText(
      """
      plugins { id 'filmstrip.root' apply false }
      def test = tasks.register('jvmTest', Test) {
        dev.jordond.filmstrip.convention.FfmpegTestInputsKt.usesHostFfmpeg(it)
      }
      tasks.register('printFfmpegInputs') {
        doLast {
          def task = test.get()
          ['ffmpegExecutable', 'ffmpegBuild', 'ffprobeExecutable'].each {
            println "input ${'$'}it=${'$'}{task.inputs.properties[it]}"
          }
          task.jvmArgumentProviders.each { it.asArguments().each { println "argument ${'$'}it" } }
          task.actions.first().execute(task)
          println "environment FILMSTRIP_FFPROBE=${'$'}{task.environment['FILMSTRIP_FFPROBE']}"
        }
      }
      def gate = tasks.register('gate', JavaExec) {
        dev.jordond.filmstrip.convention.FfmpegTestInputsKt.usesHostFfmpegTools(it)
      }
      tasks.register('printGateEnvironment') {
        doLast {
          [test.get(), gate.get()].each { task ->
            task.actions.first().execute(task)
            ['FILMSTRIP_FFMPEG', 'FILMSTRIP_FFPROBE'].each {
              println "${'$'}{task.name} ${'$'}it=${'$'}{task.environment[it]}"
            }
          }
        }
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `the ffmpeg on the PATH is the executable input and its build the build input`() {
    val ffmpeg = stubFfmpeg(pathStubs, version = "9.0.2")

    val lines = run("printFfmpegInputs")

    lines shouldContain "input ffmpegExecutable=${ffmpeg.absolutePath}"
    lines shouldContain "input ffmpegBuild=${recordOf("9.0.2")}"
    lines shouldContain "argument -D$FFMPEG_PROPERTY=${ffmpeg.absolutePath}"
  }

  @Test
  fun `FILMSTRIP_FFMPEG wins over the PATH for both inputs`() {
    stubFfmpeg(pathStubs, version = "9.0.2")
    val configured = stubFfmpeg(Files.createTempDirectory("ffmpeg-configured").toFile(), version = "8.0.1")

    val lines = run("printFfmpegInputs", configured = configured)

    lines shouldContain "input ffmpegExecutable=${configured.absolutePath}"
    lines shouldContain "input ffmpegBuild=${recordOf("8.0.1")}"
  }

  @Test
  fun `the ffprobe beside FILMSTRIP_FFMPEG wins over the one on the PATH`() {
    stubFfmpeg(pathStubs, version = "9.0.2")
    stubFfprobe(pathStubs)
    val directory = Files.createTempDirectory("ffmpeg-configured").toFile()
    val configured = stubFfmpeg(directory, version = "8.0.1")
    val sibling = stubFfprobe(directory)

    val lines = run("printFfmpegInputs", configured = configured)

    lines shouldContain "input ffprobeExecutable=${sibling.absolutePath}"
    lines shouldContain "argument -D$FFPROBE_PROPERTY=${sibling.absolutePath}"
    lines shouldContain "environment FILMSTRIP_FFPROBE=${sibling.absolutePath}"
  }

  @Test
  fun `FILMSTRIP_FFPROBE wins over the ffprobe beside ffmpeg`() {
    val directory = Files.createTempDirectory("ffmpeg-configured").toFile()
    val configured = stubFfmpeg(directory, version = "8.0.1")
    stubFfprobe(directory)
    val probe = stubFfprobe(Files.createTempDirectory("ffprobe-configured").toFile())

    val lines = run("printFfmpegInputs", configured = configured, configuredProbe = probe)

    lines shouldContain "input ffprobeExecutable=${probe.absolutePath}"
    lines shouldContain "argument -D$FFPROBE_PROPERTY=${probe.absolutePath}"
    lines shouldContain "environment FILMSTRIP_FFPROBE=${probe.absolutePath}"
  }

  @Test
  fun `the ffprobe on the PATH is used when none sits beside ffmpeg`() {
    val onPath = stubFfprobe(pathStubs)
    val configured = stubFfmpeg(Files.createTempDirectory("ffmpeg-configured").toFile(), version = "8.0.1")

    val lines = run("printFfmpegInputs", configured = configured)

    lines shouldContain "input ffprobeExecutable=${onPath.absolutePath}"
    lines shouldContain "environment FILMSTRIP_FFPROBE=${onPath.absolutePath}"
  }

  @Test
  fun `the toolchain gate gets the ffmpeg and ffprobe the test task gets`() {
    stubFfmpeg(pathStubs, version = "9.0.2")
    stubFfprobe(pathStubs)
    val directory = Files.createTempDirectory("ffmpeg-configured").toFile()
    val configured = stubFfmpeg(directory, version = "8.0.1")
    val sibling = stubFfprobe(directory)

    val lines = run("printGateEnvironment", configured = configured)

    lines shouldContain "jvmTest FILMSTRIP_FFMPEG=${configured.absolutePath}"
    lines shouldContain "jvmTest FILMSTRIP_FFPROBE=${sibling.absolutePath}"
    lines shouldContain "gate FILMSTRIP_FFMPEG=${configured.absolutePath}"
    lines shouldContain "gate FILMSTRIP_FFPROBE=${sibling.absolutePath}"
  }

  @Test
  fun `the test task runs after the toolchain gate`() {
    stubFfmpeg(pathStubs, version = "9.0.2")

    run("jvmTest", "--dry-run").joinToString("\n") shouldContain ":filmstrip-transform-ffmpeg:checkFfmpegTestToolchain"
  }

  private fun stubFfmpeg(
    directory: File,
    version: String,
  ): File =
    directory.resolve("ffmpeg").apply {
      writeText(
        """
        #!/bin/sh
        if [ "${'$'}1" = "-version" ]; then
          echo "ffmpeg version $version Copyright (c) the FFmpeg developers"
        else
          echo "x264 - core 165 - H.264 x265 (build 215) - 4.1 - H.265"
        fi
        """.trimIndent(),
      )
      setExecutable(true)
    }

  private fun stubFfprobe(directory: File): File =
    directory.resolve("ffprobe").apply {
      writeText("#!/bin/sh\n")
      setExecutable(true)
    }

  private fun recordOf(version: String): String =
    "$version libx264 core 165 libx265 4.1 arch ${System.getProperty("os.arch")}"

  private fun run(
    vararg arguments: String,
    configured: File? = null,
    configuredProbe: File? = null,
  ): List<String> {
    val environment =
      buildMap {
        put("PATH", "${pathStubs.path}${File.pathSeparator}${System.getenv("PATH")}")
        configured?.let { put("FILMSTRIP_FFMPEG", it.absolutePath) }
        configuredProbe?.let { put("FILMSTRIP_FFPROBE", it.absolutePath) }
      }
    return GradleRunner
      .create()
      .withProjectDir(project)
      .withPluginClasspath()
      .withTestKitDir(testKit)
      .withEnvironment(environment)
      .withArguments(arguments.toList())
      .build()
      .output
      .lines()
  }

  private companion object {
    val pathStubs: File = Files.createTempDirectory("ffmpeg-path-stubs").toFile()

    val testKit: File = Files.createTempDirectory("ffmpeg-inputs-testkit").toFile()
  }
}
