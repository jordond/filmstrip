package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.lowering.PREVIEW_PIXEL_FORMAT
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.readingGraphFrom
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.resolve
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Draws a composition with a Vhs in it and hands back the RGBA frames, read off the preview pump or off a written file.
 *
 * The preview runs the export's own graph, so a pump frame is what an export encodes before the encoder touches it.
 */
internal class VhsFrames {
  private val components =
    ComponentRegistry
      .Builder()
      .addEffectResolver(BuiltInEffectResolver())
      .addEffectResolver(FilterEffectResolver())
      .build()
  private val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))
  private val filmstrip =
    Filmstrip {
      ffmpegBackend()
      filters()
    }

  /**
   * One track holding [clips], with [effects] on the composition.
   */
  fun composition(
    clips: List<Clip>,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(OPAQUE_BLACK),
  ) = EditComposition(listOf(Track(clips)), effects, fill = fill)

  /**
   * The first [count] frames the preview draws from [at].
   */
  suspend fun frames(
    composition: EditComposition,
    at: Duration,
    count: Int,
    spec: ExportSpec = ExportSpec(),
  ): List<ByteArray> {
    val opened =
      engine
        .openPreview(composition, spec, at = at)
        .shouldBeInstanceOf<PreviewStreamResult.Opened>()
        .stream
        .shouldBeInstanceOf<FfmpegPreviewStream>()
    return try {
      List(count) { opened.next().shouldNotBeNull() }
    } finally {
      opened.close()
    }
  }

  /**
   * The filter graph [frames] runs for [composition].
   */
  suspend fun graphOf(
    composition: EditComposition,
    spec: ExportSpec = ExportSpec(),
  ): String {
    val (_, invocation) =
      engine.runs
        .lowered(composition, spec, null)
        .graph
        .shouldNotBeNull()
    return invocation.filterGraph
  }

  /**
   * Frame [slot] of the graph [frames] runs, as the planes it writes, with no conversion to RGBA after it.
   */
  suspend fun planesAt(
    composition: EditComposition,
    slot: Int,
    spec: ExportSpec = ExportSpec(),
  ): Yuv420Frame {
    val (toolchain, invocation) =
      engine.runs
        .lowered(composition, spec, null)
        .graph
        .shouldNotBeNull()
    val scratch = Scratch.create(engine.scratchRoot)
    try {
      val command =
        invocation
          .previewArguments(
            toolchain,
            engine.runtime.config,
            invocation.inputs.map { scratch.resolve(it.source).path },
            invocation.sidecars.map { scratch.write(it) },
            Duration.ZERO,
          ).map { if (it == PREVIEW_PIXEL_FORMAT) PLANES_FORMAT else it }
          .readingGraphFrom(scratch)
      val size = invocation.output.size
      val bytes = size.width * size.height * PLANES_BYTES_PER_TWO_PIXELS / 2
      val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
      val read = process.inputStream.readNBytes(bytes * (slot + 1))
      process.destroy()
      read.size shouldBe bytes * (slot + 1)
      return Yuv420Frame(size.width, size.height, read.copyOfRange(bytes * slot, bytes * (slot + 1)))
    } finally {
      scratch.delete()
    }
  }

  /**
   * Exports [composition] at [spec] and reads the frame in output slot [slot] back out of the file.
   */
  suspend fun exportedFrame(
    composition: EditComposition,
    slot: Int,
    spec: ExportSpec,
  ): ByteArray {
    val output = File.createTempFile("vhs-export", ".mp4").apply { deleteOnExit() }
    output.delete()
    val plan = planOf(composition, spec)
    val finished = filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList().last()
    finished.shouldBeInstanceOf<ExportStatus.Success>()
    val size = plan.output.size
    return rgbaOf(output, opensOn(slot, plan), size.width * size.height * RGBA)
  }

  /**
   * A lossless clip of a [width] by [height] picture, each column drawn by [source] from its centre's fraction of the
   * width, so a read holds the picture and not an encoder's idea of it. It is stored in [pixelFormat] and [range].
   */
  fun clipOf(
    width: Int,
    height: Int,
    source: (Double) -> DoubleArray,
    effects: List<EffectSpec> = emptyList(),
    pixelFormat: String = PLANES_FORMAT,
    range: String = "tv",
  ): Clip {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until width) {
      val rgb = source((x + 0.5) / width).map { (it * FULL).roundToInt() }
      for (y in 0 until height) image.setRGB(x, y, (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2])
    }
    val still = File.createTempFile("vhs-still", ".png").apply { deleteOnExit() }
    ImageIO.write(image, "png", still)
    val video = File.createTempFile("vhs-still", ".mkv").apply { deleteOnExit() }
    val encode =
      ProcessBuilder(
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-loop",
        "1",
        "-i",
        still.path,
        "-t",
        "2",
        "-r",
        "$VHS_RATE",
        "-vf",
        "scale=out_color_matrix=bt709:out_range=$range",
        "-colorspace",
        "bt709",
        "-color_range",
        range,
        "-c:v",
        "ffv1",
        "-pix_fmt",
        pixelFormat,
        video.path,
      ).inheritIO().start()
    encode.waitFor() shouldBe 0
    return Clip(MediaSource.of(video.path), effects = effects)
  }

  /**
   * A lossless clip holding these exact plane codes, one column each, written with no conversion on the way in.
   */
  fun clipOfPlanes(
    width: Int,
    height: Int,
    luma: (Int) -> Int,
    cb: (Int) -> Int,
    cr: (Int) -> Int,
    effects: List<EffectSpec> = emptyList(),
  ): Clip {
    val lumaEnd = width * height
    val chromaWidth = width / 2
    val cbEnd = lumaEnd + chromaWidth * (height / 2)
    val raw =
      ByteArray(cbEnd + chromaWidth * (height / 2)) { i ->
        when {
          i < lumaEnd -> luma(i % width)
          i < cbEnd -> cb((i - lumaEnd) % chromaWidth)
          else -> cr((i - cbEnd) % chromaWidth)
        }.toByte()
      }
    val frame = File.createTempFile("vhs-planes", ".yuv").apply { deleteOnExit() }
    frame.writeBytes(raw)
    val video = File.createTempFile("vhs-planes", ".mkv").apply { deleteOnExit() }
    val encode =
      ProcessBuilder(
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "rawvideo",
        "-pix_fmt",
        PLANES_FORMAT,
        "-s",
        "${width}x$height",
        "-r",
        "$VHS_RATE",
        // Named on the input as well, or ffmpeg converts to reach the output's range and floors a super-black code.
        "-colorspace",
        "bt709",
        "-color_range",
        "tv",
        "-stream_loop",
        "-1",
        "-i",
        frame.path,
        "-t",
        "2",
        "-colorspace",
        "bt709",
        "-color_range",
        "tv",
        "-c:v",
        "ffv1",
        "-pix_fmt",
        PLANES_FORMAT,
        video.path,
      ).inheritIO().start()
    encode.waitFor() shouldBe 0
    return Clip(MediaSource.of(video.path), effects = effects)
  }

  private suspend fun planOf(
    composition: EditComposition,
    spec: ExportSpec,
  ): ExportPlan =
    when (val verdict = filmstrip.plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
    }

  private fun rgbaOf(
    file: File,
    at: Duration,
    bytes: Int,
  ): ByteArray {
    val process =
      ProcessBuilder(
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-ss",
        "${at.toDouble(DurationUnit.SECONDS)}",
        "-i",
        file.path,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgba",
        "-",
      ).start()
    return process.inputStream.readNBytes(bytes).also { process.waitFor() }
  }
}

/**
 * One frame's planes as a graph wrote them, 8 bit limited range 4:2:0.
 */
internal class Yuv420Frame(
  val width: Int,
  val height: Int,
  private val bytes: ByteArray,
) {
  /**
   * The luma code at [x] and [y].
   */
  fun luma(
    x: Int,
    y: Int,
  ): Int = bytes[y * width + x].toInt() and BYTE

  /**
   * The Cb code of chroma sample [column] on chroma row [row].
   */
  fun cb(
    column: Int,
    row: Int,
  ): Int = bytes[width * height + row * (width / 2) + column].toInt() and BYTE

  /**
   * The Cr code of chroma sample [column] on chroma row [row].
   */
  fun cr(
    column: Int,
    row: Int,
  ): Int = bytes[width * height + (width / 2) * (height / 2) + row * (width / 2) + column].toInt() and BYTE
}

private const val PLANES_FORMAT = "yuv420p"
private const val PLANES_BYTES_PER_TWO_PIXELS = 3

internal const val VHS_RATE = 30
internal const val RGBA = 4
internal const val BYTE = 0xFF
internal const val FULL = 255.0
internal const val OPAQUE_BLACK = 0xFF000000.toInt()

/**
 * The channel [channel] of the pixel at [x] and [y] in a frame [width] wide.
 */
internal fun ByteArray.channelAt(
  width: Int,
  x: Int,
  y: Int,
  channel: Int,
): Int = this[(y * width + x) * RGBA + channel].toInt() and BYTE
