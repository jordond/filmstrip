package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.ffmpeg.ffmpegExportEngine
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.coroutines.flow.toList
import java.io.File
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The clip every desktop contract suite plays, downloaded by the module's fixture task.
 *
 * `jvmTest` depends on that task, so the file is there or the build failed before a test ran.
 * Nothing here skips: a suite that returns early on a missing fixture reports green without having
 * asserted anything.
 */
internal fun jvmFixtureClip(): String {
  val directory =
    System
      .getProperty(
        FIXTURES,
      ).orEmpty()
      .ifBlank { fail("$FIXTURES was not set. The jvmTest task is what provides it.") }
  val path = File(directory, CLIP_NAME)
  if (!path.isFile) fail("The fixture $path was not downloaded.")
  return path.absolutePath
}

/**
 * One trimmed clip of the fixture, with [effects] over the whole composition.
 */
internal fun jvmFixtureComposition(effects: List<EffectSpec> = emptyList()): EditComposition =
  EditComposition(
    tracks = listOf(Track(listOf(Clip(MediaSource.of(jvmFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
    effects = effects,
  )

/**
 * The frame the export writes at [position], decoded back out of the written file.
 *
 * This is the export path in full, encoder included, rather than a second run of the preview's own
 * graph: the edit is negotiated again, encoded to a real mp4, and the frame at [position] decoded
 * out of it. That is one encode further than the preview goes, which is why
 * [JvmPixelContractTest] loosens its thresholds and says by how much.
 *
 * The file is kept per composition, since the suite asks for several frames of each.
 */
@OptIn(InternalFilmstripApi::class)
internal suspend fun jvmExportFrame(
  composition: EditComposition,
  position: Duration,
): TestFrame {
  val path = exports.getOrPut(composition) { exportOf(composition) }
  return decodeFrame(path, position, FIXTURE_FRAME)
}

@OptIn(InternalFilmstripApi::class)
private suspend fun exportOf(composition: EditComposition): String {
  val engine = ffmpegExportEngine(CONTRACT_COMPONENTS)
  val plan =
    when (val verdict = engine.plan(composition, ExportSpec())) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> fail("the export refused the fixture: ${verdict.reasons.first().message}")
    }

  val output = File.createTempFile("filmstrip-jvm-export", ".mp4").also { it.deleteOnExit() }
  val finished = engine.export(plan, MediaSink.of(output.absolutePath)).toList().last()
  if (finished is ExportStatus.Failure) fail("the export failed: ${finished.error.message}")
  return output.absolutePath
}

/**
 * One frame of [path] at [at], as the tightly packed RGBA the comparison helpers take.
 *
 * Spawned rather than routed through the pump, so the frame the preview is compared against comes
 * out of the file itself and not out of anything the preview shares code with.
 */
private fun decodeFrame(
  path: String,
  at: Duration,
  size: Size,
): TestFrame {
  val process =
    ProcessBuilder(
      listOf(
        System.getenv(FFMPEG).orEmpty().ifBlank { "ffmpeg" },
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-ss",
        at.toDouble(DurationUnit.SECONDS).toString(),
        "-i",
        path,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgba",
        "pipe:1",
      ),
    ).start()

  val pixels = process.inputStream.readAllBytes()
  val stderr = process.errorStream.readAllBytes().decodeToString()
  if (process.waitFor() != 0) fail("decoding $path at $at failed: $stderr")
  if (pixels.size != size.width * size.height * CHANNELS) {
    fail("decoding $path at $at gave ${pixels.size} bytes, not a ${size.width}x${size.height} frame")
  }
  return TestFrame(pixels, size)
}

private val exports = mutableMapOf<EditComposition, String>()

private const val FIXTURES = "filmstrip.fixtures"
private const val FFMPEG = "FILMSTRIP_FFMPEG"
private const val CLIP_NAME = "apple_export_a.mp4"
private const val CHANNELS = 4

/**
 * Long enough to play through and seek inside, and a whole number of frames at the fixture's rate.
 */
internal val CLIP_LENGTH: Duration = 1500.milliseconds

/**
 * The frame the fixture decodes at, which is the frame an export of it writes.
 */
internal val FIXTURE_FRAME: Size = Size(640, 360)

/**
 * Composition times the suites compare frames at, each landing exactly on the fixture's 30fps grid.
 */
internal val PROBE_POSITIONS: List<Duration> = listOf(300.milliseconds, 900.milliseconds)

/**
 * How long one frame of the fixture runs for, at the 30fps its `FixtureSpec` pins.
 */
internal val FIXTURE_FRAME_STEP: Duration = 1.seconds / 30

/**
 * How far apart the fixture's sync samples sit, which is the keyframe interval it was encoded at.
 *
 * An upper bound rather than the exact spacing: x264 is free to place one early and does.
 */
internal val FIXTURE_SYNC_INTERVAL: Duration = 1.seconds

/**
 * A composition time well inside a group of pictures rather than near either end of one, and still
 * exactly on the fixture's frame grid.
 *
 * What tells a decode to the requested frame apart from a snap to the nearest sync sample. A
 * position at either end agrees under both, which is what a probe on the grid's edges would miss.
 */
internal val MID_GOP_POSITION: Duration = 700.milliseconds

/**
 * The components a host that registered the ffmpeg backend would hand a preview.
 *
 * Registered rather than assembled by hand, so the preview takes the engine over the runtime
 * `ffmpegBackend` built and shares one toolchain resolution and one probe cache with the exports,
 * which is the arrangement a real host gets.
 */
internal val CONTRACT_COMPONENTS: ComponentRegistry = Filmstrip { ffmpegBackend() }.components

/**
 * Every ffmpeg this JVM is still a parent of.
 *
 * Descendants rather than direct children, since a spawn goes through whatever the JDK's process
 * reaper leaves in between. Suites take a baseline before the object under test exists, because the
 * whole module shares one JVM and whichever ran first is still shutting its own children down.
 */
internal fun runningPumps(): List<ProcessHandle> =
  ProcessHandle
    .current()
    .descendants()
    .filter {
      it
        .info()
        .command()
        .orElse("")
        .contains("ffmpeg")
    }.toList()

/**
 * The running ffmpeg processes that are reading this suite's fixture.
 *
 * [runningPumps] counts every ffmpeg the JVM has spawned, and lowering an edit spawns several of
 * its own: the encoder ladder is measured by running ffmpeg against a synthetic input, once per
 * engine. Those come and go on their own schedule, so a suite that has to say something about one
 * pump asks for the processes reading the clip rather than for whatever ffmpeg is alive.
 *
 * A process whose arguments the platform will not hand over is left out. That shows up as a wait
 * that times out, rather than as an assertion that quietly stopped covering anything.
 */
internal fun runningFramePumps(): List<ProcessHandle> =
  runningPumps().filter { handle ->
    handle
      .info()
      .arguments()
      .map { arguments -> arguments.any { it.endsWith(CLIP_NAME) } }
      .orElse(false)
  }
