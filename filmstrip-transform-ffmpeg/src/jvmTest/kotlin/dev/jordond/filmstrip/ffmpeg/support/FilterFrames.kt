package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectResolver
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
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.PREVIEW_PIXEL_FORMAT
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.readingGraphFrom
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveBinding
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveFeedRequest
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.resolve
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
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
 * Draws a composition with a filter in it and hands back the RGBA frames, read off the preview pump or off a written
 * file.
 *
 * The preview runs the export's own graph, so a pump frame is what an export encodes before the encoder touches it.
 */
internal class FilterFrames(
  extraResolver: EffectResolver? = null,
  listener: DiagnosticListener? = null,
  bindFeeds: ((List<LiveFeedRequest>) -> LiveBinding)? = null,
) {
  private val components =
    ComponentRegistry
      .Builder()
      .addEffectResolver(BuiltInEffectResolver())
      .addEffectResolver(FilterEffectResolver())
      .apply {
        extraResolver?.let { addEffectResolver(it) }
        listener?.let { addDiagnosticListener(it) }
      }.build()
  val engine =
    FfmpegExportEngine(
      components,
      bindFeeds?.let { FfmpegRuntime.unshared(FfmpegConfig(), bindFeeds = it) } ?: FfmpegRuntime.of(FfmpegConfig()),
    )
  private val filmstrip =
    Filmstrip {
      ffmpegBackend()
      filters()
      extraResolver?.let { addEffectResolver(it) }
    }

  /**
   * One track holding [clips], with [effects] on the composition.
   */
  fun composition(
    clips: List<Clip>,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(OPAQUE_BLACK),
  ) = Composition(listOf(Track(clips)), effects, fill = fill)

  /**
   * The first [count] frames the preview draws from [at].
   */
  suspend fun frames(
    composition: Composition,
    at: Duration,
    count: Int,
    spec: ExportSpec = ExportSpec(),
    grade: ResolvedHdr? = null,
  ): List<ByteArray> = framesAs(composition, at, count, spec, grade) { it }

  /**
   * What [read] makes of each of the first [count] frames the preview draws from [at]. Only the frame being read is
   * held, so a long run of large frames costs one frame of heap.
   */
  suspend fun <T> framesAs(
    composition: Composition,
    at: Duration,
    count: Int,
    spec: ExportSpec = ExportSpec(),
    grade: ResolvedHdr? = null,
    read: (ByteArray) -> T,
  ): List<T> {
    val opened =
      engine
        .openPreview(composition, spec, at = at, grade = grade)
        .shouldBeInstanceOf<PreviewStreamResult.Opened>()
        .stream
        .shouldBeInstanceOf<FfmpegPreviewStream>()
    return try {
      List(count) { read(opened.next().shouldNotBeNull()) }
    } finally {
      opened.close()
    }
  }

  /**
   * The frame [slot] frames after the first one the preview draws from [at]. The frames before it are read and
   * dropped one at a time.
   */
  suspend fun frameAt(
    composition: Composition,
    at: Duration,
    slot: Int,
    spec: ExportSpec = ExportSpec(),
    grade: ResolvedHdr? = null,
  ): ByteArray {
    var last: ByteArray? = null
    framesAs(composition, at, slot + 1, spec, grade) { last = it }
    return last.shouldNotBeNull()
  }

  /**
   * Opens the preview of [composition] at [at] for a test that drives or closes it itself.
   */
  suspend fun opened(
    composition: Composition,
    at: Duration = Duration.ZERO,
  ): FfmpegPreviewStream =
    engine
      .openPreview(composition, ExportSpec(), at = at)
      .shouldBeInstanceOf<PreviewStreamResult.Opened>()
      .stream
      .shouldBeInstanceOf<FfmpegPreviewStream>()

  /**
   * What [composition] lowers to, before a form is chosen to run.
   */
  suspend fun invocationOf(composition: Composition): Invocation =
    engine.runs
      .lowered(composition, ExportSpec(), null)
      .graph
      .shouldNotBeNull()
      .invocation

  /**
   * Runs an export of [composition] to [output] through this engine, and hands back every status it reported.
   */
  suspend fun exportStatuses(
    composition: Composition,
    output: File,
  ): List<ExportStatus> {
    val plan = engine.plan(composition, ExportSpec()).shouldBeInstanceOf<Verdict.Capable>().plan
    return engine.export(plan, MediaSink.of(output.absolutePath)).toList()
  }

  /**
   * The filter graph [frames] runs for [composition].
   */
  suspend fun graphOf(
    composition: Composition,
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
   * Frame [slot] of the graph [frames] runs, as 8 bit limited range BT.709 planes with no conversion to RGBA after it.
   * A graph that already writes those codes hands them over as they are.
   */
  suspend fun planesAt(
    composition: Composition,
    slot: Int,
    spec: ExportSpec = ExportSpec(),
  ): Yuv420Frame =
    rawFrameAt(composition, slot, spec, PLANES_ARGUMENTS, 1) { width, height, bytes ->
      Yuv420Frame(width, height, bytes)
    }

  /**
   * The luma plane of frame [slot] of the graph [frames] runs at a kept grade [spec], as the ten-bit codes the graph
   * writes, rows from the top.
   */
  suspend fun tenBitLumaAt(
    composition: Composition,
    slot: Int,
    spec: ExportSpec,
  ): IntArray =
    rawFrameAt(composition, slot, spec, TEN_BIT_PLANES_ARGUMENTS, TEN_BIT_BYTES) { width, height, bytes ->
      IntArray(width * height) { (bytes[2 * it].toInt() and BYTE) or ((bytes[2 * it + 1].toInt() and BYTE) shl 8) }
    }

  private suspend fun <T> rawFrameAt(
    composition: Composition,
    slot: Int,
    spec: ExportSpec,
    format: List<String>,
    bytesPerSample: Int,
    frameOf: (width: Int, height: Int, bytes: ByteArray) -> T,
  ): T {
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
          ).flatMap { if (it == PREVIEW_PIXEL_FORMAT) format else listOf(it) }
          .readingGraphFrom(scratch)
      val size = invocation.output.size
      val bytes = size.width * size.height * PLANES_BYTES_PER_TWO_PIXELS / 2 * bytesPerSample
      val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
      val read =
        process.inputStream.run {
          skipNBytes(bytes.toLong() * slot)
          readNBytes(bytes)
        }
      process.destroy()
      read.size shouldBe bytes
      return frameOf(size.width, size.height, read)
    } finally {
      scratch.delete()
    }
  }

  /**
   * Exports [composition] at [spec] and reads the frame in output slot [slot] back out of the file.
   */
  suspend fun exportedFrame(
    composition: Composition,
    slot: Int,
    spec: ExportSpec,
  ): ByteArray = exportedFrames(composition, listOf(slot), spec).single()

  /**
   * Exports [composition] at [spec] once and reads the frame in each of [slots] back out of the file, in the order
   * given.
   */
  suspend fun exportedFrames(
    composition: Composition,
    slots: List<Int>,
    spec: ExportSpec,
  ): List<ByteArray> {
    val output = File.createTempFile("filter-export", ".mp4").apply { deleteOnExit() }
    output.delete()
    val plan = planOf(composition, spec)
    val finished = filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList().last()
    finished.shouldBeInstanceOf<ExportStatus.Success>()
    val size = plan.output.size
    return slots.map { rgbaOf(output, opensOn(it, plan), size.width * size.height * RGBA) }
  }

  /**
   * A lossless clip of a [width] by [height] picture, each column drawn by [source] from its centre's fraction of the
   * width, so a read holds the picture and not an encoder's idea of it. It is stored in [pixelFormat] and [range], and
   * on BT.601's matrix tagged with SMPTE C primaries when [onSmpteC] is set.
   */
  fun clipOf(
    width: Int,
    height: Int,
    source: (Double) -> DoubleArray,
    effects: List<EffectSpec> = emptyList(),
    pixelFormat: String = PLANES_FORMAT,
    range: String = "tv",
    onSmpteC: Boolean = false,
    rate: String = "$FILTER_RATE",
    seconds: Int = 2,
    container: String = "mkv",
    timescale: Int? = null,
  ): Clip {
    val image = imageOf(width, height) { x, _ -> source(x) }
    val still = File.createTempFile("filter-still", ".png").apply { deleteOnExit() }
    ImageIO.write(image, "png", still)
    val video = File.createTempFile("filter-still", ".$container").apply { deleteOnExit() }
    val convert =
      if (onSmpteC) {
        "scale=out_color_matrix=bt601:out_range=$range," +
          "setparams=color_primaries=smpte170m:color_trc=smpte170m:colorspace=smpte170m"
      } else {
        "scale=out_color_matrix=bt709:out_range=$range"
      }
    val encode =
      ProcessBuilder(
        hostFfmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-loop",
        "1",
        "-i",
        still.path,
        "-t",
        "$seconds",
        "-r",
        rate,
        "-vf",
        convert,
        "-colorspace",
        if (onSmpteC) "smpte170m" else "bt709",
        "-color_range",
        range,
        *if (container == "mov") MOV_COLOUR_TAGS else emptyArray(),
        *timescale?.let { arrayOf("-video_track_timescale", "$it") }.orEmpty(),
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
    val frame = File.createTempFile("filter-planes", ".yuv").apply { deleteOnExit() }
    frame.writeBytes(raw)
    val video = File.createTempFile("filter-planes", ".mkv").apply { deleteOnExit() }
    val encode =
      ProcessBuilder(
        hostFfmpeg,
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
        "$FILTER_RATE",
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
    composition: Composition,
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
        hostFfmpeg,
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

// How far a frame drawn under a Pixel reads from its reference after rounding through gbrap and back.
internal const val PIXEL_CODES = 4

internal const val PLANES_FORMAT = "yuv420p"
internal const val PLANES_BYTES_PER_TWO_PIXELS = 3
internal val PLANES_ARGUMENTS = listOf(PLANES_FORMAT, "-color_range", "tv", "-colorspace", "bt709")
internal const val TEN_BIT_FORMAT = "yuv420p10le"
internal const val TEN_BIT_ALPHA_FORMAT = "yuva420p10le"
internal val TEN_BIT_PLANES_ARGUMENTS = listOf(TEN_BIT_FORMAT)
internal const val TEN_BIT_BYTES = 2
internal const val MID = 512

internal const val FILTER_RATE = 30

// A mov keeps a frame's exact time, which a mkv rounds to a millisecond, and needs its tags asked for.
private val MOV_COLOUR_TAGS =
  arrayOf("-movflags", "+write_colr", "-color_primaries", "bt709", "-color_trc", "bt709")
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
