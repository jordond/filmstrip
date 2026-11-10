package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.GRAPH_FLAG
import dev.jordond.filmstrip.ffmpeg.internal.lowering.PREVIEW_PIXEL_FORMAT
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.readingGraphFrom
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.resolve
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * A lossless `yuva420p` clip [width] by [height] of a flat light grey, whose alpha is its column's code, so it runs
 * from clear on the left to opaque from column 255. The planes are written as they are, with no conversion on the way
 * in.
 */
internal fun FilterFrames.alphaRampClip(
  width: Int,
  height: Int,
  effects: List<EffectSpec> = emptyList(),
): Clip = alphaClip(width, height, effects, GREY_CODES) { column -> minOf(column, BYTE) }

/**
 * A clip like [alphaRampClip] in [colour], red, green and blue from 0 to 1, held as its nearest limited range BT.709
 * codes.
 */
internal fun FilterFrames.alphaRampClipOf(
  width: Int,
  height: Int,
  colour: DoubleArray,
  effects: List<EffectSpec> = emptyList(),
): Clip = alphaClip(width, height, effects, codesOf(colour)) { column -> minOf(column, BYTE) }

/**
 * A clip like [alphaRampClip] whose alpha is [code] all over, so it holds wherever a look reads it from.
 */
internal fun FilterFrames.flatAlphaClip(
  width: Int,
  height: Int,
  code: Int,
  effects: List<EffectSpec> = emptyList(),
): Clip = alphaClip(width, height, effects, GREY_CODES) { code }

private fun FilterFrames.alphaClip(
  width: Int,
  height: Int,
  effects: List<EffectSpec>,
  codes: IntArray,
  alphaOfColumn: (Int) -> Int,
): Clip {
  val luma = width * height
  val chroma = luma / 4
  val raw =
    ByteArray(luma * 2 + chroma * 2) {
      when {
        it < luma -> codes[0]
        it < luma + chroma -> codes[1]
        it < luma + chroma * 2 -> codes[2]
        else -> alphaOfColumn((it - luma - chroma * 2) % width)
      }.toByte()
    }
  val frame = File.createTempFile("filter-alpha", ".yuv").apply { deleteOnExit() }
  frame.writeBytes(raw)
  val video = File.createTempFile("filter-alpha", ".mkv").apply { deleteOnExit() }
  val tags = arrayOf("-colorspace", "bt709", "-color_range", "tv")
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
      ALPHA_FORMAT,
      "-s",
      "${width}x$height",
      "-r",
      "$FILTER_RATE",
      // Named on the input as well, or ffmpeg converts to reach the output's range and moves alpha from 128 up.
      *tags,
      "-stream_loop",
      "-1",
      "-i",
      frame.path,
      "-t",
      "2",
      *tags,
      "-c:v",
      "ffv1",
      "-pix_fmt",
      ALPHA_FORMAT,
      video.path,
    ).inheritIO().start()
  encode.waitFor() shouldBe 0
  return Clip(MediaSource.of(video.path), effects = effects)
}

/**
 * The alpha of frame [slot] of the one clip in [composition], read where the clip's chain hands it to its placement and
 * before the placement drops it, rows from the top.
 */
internal suspend fun FilterFrames.alphaAt(
  composition: Composition,
  slot: Int,
): IntArray {
  val (toolchain, invocation) =
    engine.runs
      .lowered(composition, ExportSpec(), null)
      .graph
      .shouldNotBeNull()
  val scratch = Scratch.create(engine.scratchRoot)
  try {
    val arguments =
      invocation
        .previewArguments(
          toolchain,
          engine.runtime.config,
          invocation.inputs.map { scratch.resolve(it.source).path },
          invocation.sidecars.map { scratch.write(it) },
          Duration.ZERO,
        ).map { if (it == PREVIEW_PIXEL_FORMAT) "gray" else it }
    val graph = arguments.indexOf(GRAPH_FLAG) + 1
    withClue("the placement drops the clip's alpha here") { arguments[graph] shouldContain PLACED }
    withClue("the preview takes the frame to its codes here") { arguments[graph] shouldContain SHOWN }
    val tapped =
      arguments.mapIndexed { index, it ->
        if (index == graph) it.replace(PLACED, "$PLACED_ALPHA[v0]").replace(SHOWN, "null[vshown]") else it
      }
    val command = tapped.readingGraphFrom(scratch)
    val size = invocation.output.size
    val bytes = size.width * size.height
    val log = File.createTempFile("filter-alpha", ".log").apply { deleteOnExit() }
    val process = ProcessBuilder(command).redirectError(log).start()
    val read = process.inputStream.readNBytes(bytes * (slot + 1))
    process.destroy()
    withClue(log.readText().takeLast(ERROR_TAIL)) { read.size shouldBe bytes * (slot + 1) }
    return IntArray(bytes) { read[bytes * slot + it].toInt() and BYTE }
  } finally {
    scratch.delete()
  }
}

// Luma, blue difference and red difference codes of a colour on BT.709's matrix in limited range.
private fun codesOf(colour: DoubleArray): IntArray {
  val luma = KR * colour[0] + (1 - KR - KB) * colour[1] + KB * colour[2]
  return intArrayOf(
    (LUMA_FLOOR + LUMA_SPAN * luma).roundToInt(),
    (MID_CHROMA + CHROMA_SPAN * (colour[2] - luma) / (2 * (1 - KB))).roundToInt(),
    (MID_CHROMA + CHROMA_SPAN * (colour[0] - luma) / (2 * (1 - KR))).roundToInt(),
  )
}

private const val ALPHA_FORMAT = "yuva420p"
private const val MID_CHROMA = 128
private val GREY_CODES = intArrayOf(180, MID_CHROMA, MID_CHROMA)
private const val KR = 0.2126
private const val KB = 0.0722
private const val LUMA_FLOOR = 16
private const val LUMA_SPAN = 219
private const val CHROMA_SPAN = 224
private const val ERROR_TAIL = 2000
private const val PLACED = "format=pix_fmts=yuv420p[v0]"
private const val PLACED_ALPHA = "format=pix_fmts=$ALPHA_FORMAT,alphaextract"
private const val SHOWN = "format=pix_fmts=yuv420p[vshown]"
