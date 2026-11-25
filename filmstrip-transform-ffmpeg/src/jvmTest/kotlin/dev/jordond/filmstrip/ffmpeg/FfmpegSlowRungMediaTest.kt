package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegEncoders
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeOperation
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.RESOLUTION_LADDER
import dev.jordond.filmstrip.transform.internal.negotiation.fittedBetween
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Exports through a real ffmpeg whose test encode of one ladder rung never finishes, the way an 8K software encode
 * stalls on a runner short of memory.
 */
class FfmpegSlowRungMediaTest {
  private val fixture = FfmpegExportFixture()
  private val directory = Files.createTempDirectory("filmstrip-slow-rung").toFile()
  private val encodes = File(directory, "encodes")
  private val largest = RESOLUTION_LADDER.first()
  private val smallest = RESOLUTION_LADDER.last()

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `a small export succeeds when the largest rung never finishes`() =
    runTest(timeout = TIMEOUT) {
      withClue("the fixtures are absent") { fixture.available() shouldBe true }
      val engine = engineStalling(largest)
      val source = MediaSource.of(fixture.landscape.absolutePath)
      val output = File(directory, "small.mp4")

      val plan = engine.plan(compositionOf { clip(source) }, ExportSpec(heightPx = SMALL_HEIGHT)).planned()
      val finished = engine.export(plan, MediaSink.of(output.absolutePath)).toList().last()

      withClue((finished as? ExportStatus.Failure)?.error?.message) {
        finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }

  @Test
  fun `a small export encodes no test frame larger than it needs or with an encoder it does not use`() =
    runTest(timeout = TIMEOUT) {
      withClue("the fixtures are absent") { fixture.available() shouldBe true }
      val engine = engineStalling(largest)
      val source = MediaSource.of(fixture.landscape.absolutePath)

      val plan = engine.plan(compositionOf { clip(source) }, ExportSpec(heightPx = SMALL_HEIGHT)).planned()

      val asked = encodes.readLines()
      asked.shouldNotBeEmpty()
      asked.filter { it.contains(largest.asked()) }.shouldBeEmpty()
      val used =
        plan.output.videoCodec
          .ffmpegEncoders()
          .map { "-c:v ${it.name} " }
      asked.filterNot { line -> used.any { line.contains(it) } }.shouldBeEmpty()
    }

  @Test
  fun `an export still refuses by name when the rung it needs never finishes`() =
    runTest(timeout = TIMEOUT) {
      withClue("the fixtures are absent") { fixture.available() shouldBe true }
      val engine = engineStalling(smallest)
      val source = MediaSource.of(fixture.landscape.absolutePath)

      val verdict = engine.plan(compositionOf { clip(source) }, ExportSpec(heightPx = SMALL_HEIGHT))

      val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
      reason.shouldBeInstanceOf<FilmstripError.TimedOut>().operation shouldBe ProbeOperation.ENCODER_TEST
    }

  @Test
  fun `a plan read a rung at a time lands on the frame the whole measurement gives it`() =
    runTest(timeout = TIMEOUT) {
      withClue("the fixtures are absent") { fixture.available() shouldBe true }
      val source = MediaSource.of(fixture.portrait.absolutePath)
      val ceiling = smallest
      val measured =
        engineOver(refusedAbove = ceiling)
          .capabilities()
          .shouldBeInstanceOf<CapabilitiesResult.Success>()
          .capabilities
      val natural =
        fixture.filmstrip
          .probe(source)
          .shouldBeInstanceOf<ProbeResult.Success>()
          .info.video!!
          .displaySize
      withClue("the fixture fits under $ceiling") { (natural.height > ceiling.height) shouldBe true }
      val covering = RESOLUTION_LADDER.last { it.width >= natural.width && it.height >= natural.height }
      withClue("the rung covering the fixture is $ceiling") { (covering == ceiling) shouldBe false }

      listOf(null, natural.height / 2).forEach { height ->
        val engine = engineOver(refusedAbove = ceiling)
        // Hevc for an H264 fixture, so the plan transcodes rather than copying the frame across whole.
        val spec = ExportSpec(heightPx = height, videoCodec = VideoCodec.Hevc)
        val plan = engine.plan(compositionOf { clip(source) }, spec).planned()
        val encoder = measured.encoderFor(plan.output.videoCodec).shouldNotBeNull()
        val asked = natural.atHeight(height)

        withClue("asked for $asked") {
          plan.output.size shouldBe asked.fittedBetween(encoder.minSize, encoder.maxSize, encoder.sizeAlignment)
        }
      }
    }

  @Test
  fun `the plan an edit falls back on lands on the frame the whole measurement gives it`() =
    runTest(timeout = TIMEOUT) {
      withClue("the fixtures are absent") { fixture.available() shouldBe true }
      val source = MediaSource.of(fixture.portrait.absolutePath)
      val ceiling = RESOLUTION_LADDER[RESOLUTION_LADDER.size - 2]
      val measured =
        engineOver(refusedAbove = ceiling)
          .capabilities()
          .shouldBeInstanceOf<CapabilitiesResult.Success>()
          .capabilities
      val natural =
        fixture.filmstrip
          .probe(source)
          .shouldBeInstanceOf<ProbeResult.Success>()
          .info.video!!
          .displaySize
      // No resolver here claims the crop, so the fallback drops it and asks for the whole portrait frame, which the
      // rung holding the square crop is too short for.
      val cropped =
        Composition(listOf(Track(listOf(Clip(source)))), effects = listOf(Crop(AspectRatio.Square, Fit.Crop)))
      val spec = ExportSpec(videoCodec = VideoCodec.Hevc)

      val verdict = engineOver(refusedAbove = ceiling).plan(cropped, spec).shouldBeInstanceOf<Verdict.Incapable>()

      val fallback = verdict.withoutUnsupported.shouldNotBeNull().plan
      val encoder = measured.encoderFor(fallback.output.videoCodec).shouldNotBeNull()
      fallback.output.size shouldBe natural.fittedBetween(encoder.minSize, encoder.maxSize, encoder.sizeAlignment)
    }

  @Test
  fun `an edit refused before it reaches an encoder is refused for itself when the largest rung never finishes`() =
    runTest(timeout = TIMEOUT) {
      val engine = engineStalling(largest)

      val verdict = engine.plan(Composition(tracks = emptyList()), ExportSpec())

      verdict
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()
        .shouldBeInstanceOf<FilmstripError.InvalidComposition>()
      encodes
        .takeIf { it.isFile }
        ?.readLines()
        .orEmpty()
        .filter { it.contains(largest.asked()) }
        .shouldBeEmpty()
    }

  @Test
  fun `asking for every capability still measures the largest rung and names it when it never finishes`() =
    runTest(timeout = TIMEOUT) {
      val engine = engineStalling(largest)

      val failure = engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Failure>()

      failure.error
        .shouldBeInstanceOf<FilmstripError.TimedOut>()
        .message
        .contains(largest.asked()) shouldBe true
    }

  private fun Verdict.planned() =
    when (this) {
      is Verdict.Capable -> plan
      is Verdict.Degraded -> plan
      is Verdict.Incapable -> error("the edit cannot run: ${reasons.map { it.message }}")
    }

  private fun Size.atHeight(height: Int?): Size =
    if (height == null) this else Size((height * width.toFloat() / this.height).roundToInt(), height)

  private suspend fun engineStalling(stalled: Size): FfmpegExportEngine = engineOver(stalled = stalled)

  // An engine over a stand-in for the host's ffmpeg that writes down every test encode, sleeps through the ones at
  // [stalled] so the deadline stops them, and fails the ones larger than [refusedAbove] on either side.
  private suspend fun engineOver(
    stalled: Size? = null,
    refusedAbove: Size? = null,
  ): FfmpegExportEngine {
    val real =
      ToolchainLocator(FfmpegConfig(), ProcessRunner())
        .toolchain()
        .shouldBeInstanceOf<ToolchainResult.Available>()
        .toolchain
    val ffmpeg =
      File(directory, "ffmpeg").apply {
        writeText(
          "#!/bin/sh\n" +
            "case \"$*\" in *lavfi*color=s=*) echo \"$*\" >> '${encodes.path}' ;; esac\n" +
            stalled?.let { "case \"$*\" in *color=s=${it.asked()}:*) exec sleep 600 ;; esac\n" }.orEmpty() +
            RESOLUTION_LADDER
              .filter { refusedAbove != null && (it.width > refusedAbove.width || it.height > refusedAbove.height) }
              .joinToString("") { "case \"$*\" in *color=s=${it.asked()}:*) exit 1 ;; esac\n" } +
            "exec '${real.ffmpeg}' \"$@\"\n",
        )
        setExecutable(true)
      }
    val runtime =
      FfmpegRuntime.unshared(
        FfmpegConfig(executablePath = ffmpeg.path, probePath = real.ffprobe),
        deadline = { DEADLINE },
      )
    return FfmpegExportEngine(ComponentRegistry.Builder().build(), runtime)
  }

  private fun Size.asked(): String = "${width}x$height"

  private companion object {
    const val SMALL_HEIGHT = 90
    val DEADLINE = 3.seconds
  }
}
