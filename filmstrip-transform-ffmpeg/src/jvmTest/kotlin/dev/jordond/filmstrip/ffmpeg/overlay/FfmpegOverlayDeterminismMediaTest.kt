package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * An animated overlay drawn several times, with every core of the host kept busy, draws the same frames each time.
 *
 * The animation moves, resizes and fades the overlay on every frame, so a value landing one frame early or late on
 * either run changes that frame's hash.
 */
class FfmpegOverlayDeterminismMediaTest {
  private val fixture = FfmpegExportFixture()
  private val frames = FilterFrames()

  @Test
  fun `an animated overlay writes the same frames on every run`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertSameFrames(fixture.landscape, ExportSpec())
    }

  @Test
  fun `an animated overlay on a kept grade writes the same frames on every run`() =
    runTest(timeout = KEPT_GRADE_TIMEOUT) {
      if (!fixture.hdr.isFile) return@runTest

      assertSameFrames(fixture.hdr, COLOR_READ_HDR_SPEC)
    }

  // Each run reads the frames the export's own graph hands its encoder, through the preview pump, since a video
  // encoder is free to write the same frames differently on a loaded host.
  private suspend fun assertSameFrames(
    source: File,
    spec: ExportSpec,
  ) {
    val overlay = fixture.watermark(animation = JUMPY)
    val composition =
      compositionOf {
        clip(MediaSource.of(source.absolutePath))
        effects { add(overlay) }
      }

    val runs =
      List(RUNS) { busy { frames.framesAs(composition, Duration.ZERO, FRAMES, spec) { it.contentHashCode() } } }

    runs.first().shouldNotBeEmpty()
    runs.drop(1).forEachIndexed { run, hashes ->
      val differing = hashes.indices.filter { hashes[it] != runs.first()[it] }
      withClue(
        "run ${run + 2} drew ${differing.size} frames unlike the first run's, from frame ${differing.firstOrNull()}",
      ) {
        hashes shouldBe runs.first()
      }
    }
  }

  // This test JVM's share of the cores spins while block runs, so the graph's threads are scheduled the way a loaded
  // host schedules them. A run with several test JVMs splits the host between them, and spinning every core would
  // starve the tests the other JVMs are running.
  private suspend fun <T> busy(block: suspend () -> T): T {
    val spinning = AtomicBoolean(true)
    val forks = System.getProperty(TEST_FORKS)?.toIntOrNull() ?: 1
    val burners =
      List((Runtime.getRuntime().availableProcessors() / forks).coerceAtLeast(1)) {
        Thread {
          var sink = 0L
          while (spinning.get()) sink += System.nanoTime() and 1L
          if (sink == Long.MIN_VALUE) println(sink)
        }.apply {
          isDaemon = true
          start()
        }
      }
    return try {
      block()
    } finally {
      spinning.set(false)
      burners.forEach { it.join() }
    }
  }

  private companion object {
    const val RUNS = 3

    // A kept grade draws every frame through the HDR overlay graph, about twenty times the SDR path's cost, so its
    // three runs took 70 s of the shared two minutes on an ffmpeg 8 CI host.
    val KEPT_GRADE_TIMEOUT = 5.minutes

    // How many test JVMs the build runs at once, set by -PtestForks.
    const val TEST_FORKS = "filmstrip.testForks"

    // A second and a half at 30 fps, inside both fixtures.
    const val FRAMES = 45

    // A new offset, size and opacity on every output frame, none of them a neighbour's.
    val JUMPY =
      OverlayAnimation { time ->
        val frame = (time.elapsed.inWholeMicroseconds * FRAMES_PER_SECOND / MICROS).toInt()
        OverlayFrame(
          opacity = OPACITY_FLOOR + (frame * OPACITY_STEP % STEPS) / STEPS.toFloat() * (1f - OPACITY_FLOOR),
          offset = OverlayOffset((frame * X_STEP % STEPS) / OFFSET_SCALE, (frame * Y_STEP % STEPS) / OFFSET_SCALE),
          scale = 1f + (frame * SCALE_STEP % STEPS) / SCALE_SCALE,
        )
      }

    const val FRAMES_PER_SECOND = 30L
    const val MICROS = 1_000_000L
    const val STEPS = 21
    const val OPACITY_STEP = 13
    const val X_STEP = 37
    const val Y_STEP = 53
    const val SCALE_STEP = 11
    const val OFFSET_SCALE = 50f
    const val SCALE_SCALE = 20f
    const val OPACITY_FLOOR = 0.2f
  }
}
