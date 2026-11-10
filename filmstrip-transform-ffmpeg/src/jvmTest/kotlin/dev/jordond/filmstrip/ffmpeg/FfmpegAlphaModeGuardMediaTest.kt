package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.effects.spatial.Vignette
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.ffmpeg.internal.lowering.arguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewSeek
import dev.jordond.filmstrip.ffmpeg.internal.lowering.readingGraphFrom
import dev.jordond.filmstrip.ffmpeg.internal.process.ChosenForm
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.chooseForm
import dev.jordond.filmstrip.ffmpeg.internal.process.resolve
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.alphaRampClip
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Runs the graph each look and the blurred fill lower to, and an overlay on a kept HDR grade, and reads ffmpeg's
 * verbose log for an alpha mode converter it inserted on its own.
 *
 * ffmpeg 9 tags a frame from `premultiply` premultiplied and one from `unpremultiply` straight, and a filter reading
 * two frames tagged apart gets a `premultiply_dynamic` inserted before one of them. Each look runs on a clip whose
 * alpha ramps from clear to opaque, after a contain crop that adds clear bars, in its drawn form and, for a look with
 * live planes, in its live form too. ffmpeg 8 tags no alpha mode and inserts none, so there this reads nothing.
 */
class FfmpegAlphaModeGuardMediaTest {
  private val draw = FilterFrames()
  private val fixture = FfmpegExportFixture()

  @Test
  fun `no look gets an alpha mode converter inserted`() =
    runBlocking<Unit> {
      LOOKS.forEach { look ->
        val composition = draw.composition(listOf(draw.alphaRampClip(WIDTH, HEIGHT, listOf(CONTAIN, look))))
        withClue("$look") { insertedConverters(composition, live = false).shouldBeEmpty() }
      }
    }

  @Test
  fun `no look on live planes gets an alpha mode converter inserted`() =
    runBlocking<Unit> {
      LIVE_LOOKS.forEach { look ->
        val composition = draw.composition(listOf(draw.alphaRampClip(WIDTH, HEIGHT, listOf(CONTAIN, look))))
        withClue("$look") { insertedConverters(composition, live = true).shouldBeEmpty() }
      }
    }

  @Test
  fun `a blurred fill gets no alpha mode converter inserted`() =
    runBlocking<Unit> {
      val clip = draw.alphaRampClip(WIDTH, HEIGHT, listOf(CONTAIN))
      val composition = draw.composition(listOf(clip), fill = Fill.Blurred())

      insertedConverters(composition, live = false).shouldBeEmpty()
    }

  @Test
  fun `an overlay on a kept HDR grade gets no alpha mode converter inserted`() =
    runBlocking<Unit> {
      val overlay =
        ImageOverlay(ImageSource.ofBytes(pngOf(TRANSLUCENT, OVERLAY_SIZE)), Corner.TopStart, opacity = HALF_OPACITY)
      val composition =
        draw.composition(listOf(Clip(MediaSource.of(fixture.hdr.absolutePath))), listOf(CONTAIN, overlay))
      val (toolchain, invocation) =
        draw.engine.runs
          .lowered(composition, ExportSpec(hdr = HdrMode.KeepHdr), null)
          .graph
          .shouldNotBeNull()
      val output = File.createTempFile("alpha-mode", ".mp4").apply { deleteOnExit() }
      val scratch = Scratch.create(draw.engine.scratchRoot)
      try {
        val command =
          invocation
            .arguments(
              toolchain,
              draw.engine.runtime.config,
              invocation.inputs.map { scratch.resolve(it.source).path },
              invocation.sidecars.map { scratch.write(it) },
              output.path,
            ).let { it.dropLast(1) + listOf(FRAMES_FLAG, HDR_FRAMES, it.last()) }
            .verbose()
            .readingGraphFrom(scratch)

        convertersIn(command).shouldBeEmpty()
      } finally {
        scratch.delete()
        output.delete()
      }
    }

  private suspend fun insertedConverters(
    composition: Composition,
    live: Boolean,
  ): List<String> {
    val (toolchain, planned) =
      draw.engine.runs
        .lowered(composition, ExportSpec(), null)
        .graph
        .shouldNotBeNull()
    val chosen =
      if (live) {
        chooseForm(planned, planned.previewSeek(Duration.ZERO), draw.engine.runtime.bindFeeds)
          .also { it.feeds.shouldNotBeNull() }
      } else {
        ChosenForm(planned, null, null)
      }
    val invocation = chosen.invocation
    val scratch = Scratch.create(draw.engine.scratchRoot)
    try {
      val command =
        invocation
          .previewArguments(
            toolchain,
            draw.engine.runtime.config,
            invocation.inputs.map { scratch.resolve(it.source, chosen.feeds).path },
            invocation.sidecars.map { scratch.write(it) },
            Duration.ZERO,
          ).verbose()
          .readingGraphFrom(scratch)
      chosen.feeds?.start()
      return convertersIn(command)
    } finally {
      chosen.feeds?.close()
      scratch.delete()
    }
  }

  // Runs command to its end and reads the alpha mode converters its log says ffmpeg inserted.
  private fun convertersIn(command: List<String>): List<String> {
    val log = File.createTempFile("alpha-mode", ".log").apply { deleteOnExit() }
    val process =
      ProcessBuilder(command)
        .redirectInput(ProcessBuilder.Redirect.from(File(NO_INPUT)))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(log)
        .start()
    try {
      withClue("ffmpeg ran past ${RUN_SECONDS}s") { process.waitFor(RUN_SECONDS, TimeUnit.SECONDS) shouldBe true }
    } finally {
      if (process.isAlive) process.destroyForcibly()
    }
    val text = log.readText()
    withClue(text.takeLast(ERROR_TAIL)) { process.exitValue() shouldBe 0 }
    return INSERTED
      .findAll(text)
      .map { it.groupValues[1] }
      .filter { ALPHA_MODE in it }
      .toList()
  }

  private fun List<String>.verbose(): List<String> =
    mapIndexed { i, it -> if (getOrNull(i - 1) == LOG_LEVEL) VERBOSE else it }

  private companion object {
    const val WIDTH = 256
    const val HEIGHT = 64
    const val LOG_LEVEL = "-loglevel"
    const val VERBOSE = "verbose"
    const val ALPHA_MODE = "premultiply"
    const val RUN_SECONDS = 60L
    const val ERROR_TAIL = 2000
    const val NO_INPUT = "/dev/null"
    const val FRAMES_FLAG = "-frames:v"
    const val HDR_FRAMES = "3"
    const val TRANSLUCENT = 0x80E67814.toInt()
    const val HALF_OPACITY = 0.5f
    val INSERTED = Regex("""auto-inserting filter '([^']+)'""")
    val CONTAIN = Crop(AspectRatio.Square, Fit.Contain)

    val LOOKS: List<EffectSpec> =
      listOf(
        GaussianBlur(sigma = 0.02f),
        Vignette(),
        Film(),
        Film.Super8,
        Crt(),
        NightVision(),
        Glitch(),
        Vhs(),
        Pixel(),
      )

    // The looks that lower a live form beside their drawn one.
    val LIVE_LOOKS: List<EffectSpec> = listOf(Film(), NightVision(), Vhs())
  }
}
