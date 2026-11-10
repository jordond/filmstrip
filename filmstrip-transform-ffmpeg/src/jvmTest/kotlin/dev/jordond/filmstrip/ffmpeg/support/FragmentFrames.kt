package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where [BEFORE_ZERO] puts a source's first frame, in seconds.
 */
internal const val BEFORE_ZERO_SECONDS: Double = -0.04

/**
 * Moves a source's frames to start at [BEFORE_ZERO_SECONDS], ahead of any map drawn at zero, as a lavfi chain's tail.
 */
internal val BEFORE_ZERO: String = ",setpts=PTS$BEFORE_ZERO_SECONDS/TB"

/**
 * The fragment [resolver] lowers [spec] to on a [frame] sized SDR picture.
 */
internal fun fragmentOf(
  resolver: EffectResolver,
  spec: EffectSpec,
  frame: Size,
): FilterFragment {
  val attributes =
    Attributes(
      inputSize = frame,
      outputSize = frame,
      layoutSize = frame,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = FILTER_RATE.toFloat(),
      span = TimeRange(Duration.ZERO, 1.seconds),
    )
  return resolver
    .resolve(spec, FILTER_GRAPH, attributes)
    .shouldBeInstanceOf<EffectResolution.Resolved>()
    .effect.fragment
}

/**
 * A gray [frame] sized picture in limited range BT.709 whose luma codes are the `geq` expression [luma], as a lavfi
 * chain for [fragmentFrame] to read.
 */
internal fun graySource(
  frame: Size,
  luma: String,
): String =
  "color=c=black:s=${frame.width}x${frame.height}:r=$FILTER_RATE:d=0.04,format=yuv420p," +
    "geq=lum='$luma':cb=$NEUTRAL_CHROMA:cr=$NEUTRAL_CHROMA,setparams=range=tv:colorspace=bt709"

/**
 * Runs [fragment]'s branches over the frame [source] draws and hands it back as 4:2:0 planes.
 *
 * Each sidecar is written to a file of its own for the graph's `movie` to open. A null [fragment] hands back the
 * frame as [source] drew it.
 */
internal fun fragmentFrame(
  source: String,
  frame: Size,
  fragment: FilterFragment? = null,
): Yuv420Frame {
  val directory = File.createTempFile("fragment-frame", "").apply { delete() }
  directory.mkdirs()
  try {
    val paths =
      fragment?.sidecars.orEmpty().withIndex().associate { (index, sidecar) ->
        val file = File(directory, "sidecar$index.${sidecar.extension}")
        file.writeBytes(sidecar.bytes)
        sidecar.placeholder to file.path
      }
    val graph = if (fragment == null) source else graphOf(source, fragment.branches, paths)
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        graph,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "yuv420p",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val out = ByteArrayOutputStream()
    process.inputStream.copyTo(out)
    process.waitFor() shouldBe 0
    out.size() shouldBe frame.width * frame.height * 3 / 2
    return Yuv420Frame(frame.width, frame.height, out.toByteArray())
  } finally {
    directory.deleteRecursively()
  }
}

// The source on the pad the branches read as their input, and the last branch's pad as the graph's output.
private fun graphOf(
  source: String,
  branches: List<FilterBranch>,
  paths: Map<String, String>,
): String {
  val last = branches.last().writes.single()
  val pad = { name: String -> if (name == last) "[out0]" else "[$name]" }
  return "$source[${FilterBranch.INPUT}];" +
    branches.joinToString(";") { branch ->
      val nodes = branch.nodes.ifEmpty { listOf(FilterNode("split", "outputs" to "${branch.writes.size}")) }
      branch.reads.joinToString("") { "[$it]" } + nodes.joinToString(",") { it.rendered(paths) } +
        branch.writes.joinToString("") { pad(it) }
    }
}

private fun FilterNode.rendered(paths: Map<String, String>): String =
  if (arguments.isEmpty()) {
    name
  } else {
    name + "=" + arguments.joinToString(":") { "${it.key}='${paths[it.value] ?: it.value}'" }
  }

private val FILTER_GRAPH =
  RenderCapabilities(
    api = RenderApi.FilterGraph,
    supportsFragmentShader = false,
    supportsComputeShader = false,
    supportsHdr = false,
    colorSpaces = setOf(ColorSpace.Bt709),
    maxTextureSize = 16_384,
    features = emptySet(),
  )

private const val NEUTRAL_CHROMA = 128
