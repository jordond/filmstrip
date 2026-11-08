package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.ffmpeg.support.regionAverage
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * What every SD colour test shares, the bars it writes and the reads it holds them to.
 */
internal abstract class SdColorTestCase {
  protected val fixture = FfmpegExportFixture()
  protected val folder = createTempDirectory("filmstrip-sd-color").toFile()
  private val engine =
    FfmpegExportEngine(
      ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      FfmpegRuntime.of(FfmpegConfig()),
    )

  @AfterTest
  fun cleanUp() {
    folder.deleteRecursively()
  }

  protected suspend fun previewFrame(
    composition: EditComposition,
    spec: ExportSpec = ExportSpec(),
  ): ByteArray {
    val stream =
      engine
        .openPreview(composition, spec)
        .shouldBeInstanceOf<PreviewStreamResult.Opened>()
        .stream
    return try {
      stream.next().shouldNotBeNull()
    } finally {
      stream.close()
    }
  }

  protected fun ByteArray.readAt(xFraction: Double): DoubleArray {
    val sums = DoubleArray(RGB)
    val left = (CLIP_WIDTH * xFraction).toInt() - READ_SIDE / 2
    for (row in CLIP_HEIGHT / 2 - READ_SIDE / 2 until CLIP_HEIGHT / 2 + READ_SIDE / 2) {
      for (column in left until left + READ_SIDE) {
        val at = (row * CLIP_WIDTH + column) * RGBA
        repeat(RGB) { sums[it] += (this[at + it].toInt() and BYTE_MASK).toDouble() }
      }
    }
    return DoubleArray(RGB) { sums[it] / (READ_SIDE * READ_SIDE) / MAX_CODE }
  }

  protected fun readOf(
    file: File,
    xFraction: Double,
    height: Int = CLIP_HEIGHT,
    at: Duration = Duration.ZERO,
  ): DoubleArray {
    val width = height * CLIP_WIDTH / CLIP_HEIGHT
    val read =
      regionAverage(
        file.absolutePath,
        (width * xFraction).toInt() - READ_SIDE / 2,
        height / 2 - READ_SIDE / 2,
        READ_SIDE,
        at,
      )
    return doubleArrayOf(read.first / MAX_CODE, read.second / MAX_CODE, read.third / MAX_CODE)
  }

  protected fun assertWithin(
    actual: DoubleArray,
    expected: DoubleArray,
    label: String,
  ) {
    withClue("$label read ${actual.inCodes()} against ${expected.inCodes()}") {
      actual.indices.all { abs(actual[it] - expected[it]) * MAX_CODE <= SD_CODES }.shouldBeTrue()
    }
  }

  private fun DoubleArray.inCodes(): List<Double> = map { it * MAX_CODE }

  protected fun tagsOf(file: File): List<String> {
    val process =
      ProcessBuilder(
        hostFfprobe,
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-show_entries",
        "stream=color_primaries,color_transfer,color_space",
        "-of",
        "csv=p=0",
        file.absolutePath,
      ).start()
    val output = process.inputReader().readText().trim()
    process.waitFor() shouldBe 0
    return output.split(",")
  }

  protected fun clip(
    name: String,
    tags: String?,
    matrix: String? = "bt601",
    height: Int = CLIP_HEIGHT,
  ): File {
    val file = File(folder, name)
    val convert = matrix?.let { "scale=out_color_matrix=$it," }.orEmpty()
    val tagged = tags?.let { ",setparams=$it" }.orEmpty()
    write(name, file, barsFilter(convert + "format=yuv420p" + tagged, height), listOf("-c:v", "libx264", "-crf", "10"))
    return file
  }

  protected fun hdrClip(name: String): File {
    val file = File(folder, name)
    write(name, file, barsFilter("format=yuv420p10le,setparams=$TAGS_PQ"), listOf("-c:v", "libx264", "-crf", "10"))
    return file
  }

  protected fun barsFilter(
    tail: String,
    height: Int = CLIP_HEIGHT,
  ): String {
    val width = height * CLIP_WIDTH / CLIP_HEIGHT / BARS.size
    val bars = BARS.joinToString("") { "color=c=$it:s=${width}x$height:r=$RATE:d=$SECONDS[$it];" }
    return bars + BARS.joinToString("") { "[$it]" } + "hstack=${BARS.size},$tail"
  }

  protected fun write(
    name: String,
    file: File,
    filter: String,
    arguments: List<String>,
  ) {
    val command =
      listOf(hostFfmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", filter) +
        arguments +
        listOf("-preset", "veryfast", file.absolutePath)
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val log = process.inputReader().readText()
    withClue("ffmpeg could not write $name: $log") { process.waitFor() shouldBe 0 }
  }

  protected companion object {
    const val CLIP_WIDTH = 640
    const val CLIP_HEIGHT = 360
    const val SMALLER_HEIGHT = 240
    const val RATE = 30
    const val SECONDS = 0.5
    const val READ_SIDE = 16
    const val MAX_CODE = 255.0
    const val BT709 = 1
    const val RGB = 3
    const val RGBA = 4
    const val BYTE_MASK = 0xFF
    const val UPSCALED_HEIGHT = 720
    const val ODD_READ = 100

    // Two H.264 encodes of one frame move a colour by a few codes on their own.
    const val SD_CODES = 4.0

    val BARS = listOf("0xC88C6E", "0x78B482", "0x7882BE", "0xBEAA6E", "0x33CC80")
    val BAR_CENTRES = BARS.indices.map { (it + 0.5) / BARS.size }

    // On setparams, since an encoder writes what its frames say over a -colorspace or -color_primaries flag.
    const val TAGS_170M = "colorspace=smpte170m:color_primaries=smpte170m:color_trc=smpte170m"
    const val TAGS_EBU = "colorspace=bt470bg:color_primaries=bt470bg:color_trc=smpte170m"
    const val TAGS_709 = "colorspace=bt709:color_primaries=bt709:color_trc=bt709"
    const val TAGS_PQ = "colorspace=bt2020nc:color_primaries=bt2020:color_trc=smpte2084"

    val BT709_TAGS = listOf("bt709", "bt709", "bt709")
    val UNKNOWN_TAGS = listOf("unknown", "unknown", "unknown")
    val KEPT_CLIPS =
      listOf(
        Triple("smpte170m", TAGS_170M, SMPTE_C_PRIMARIES),
        Triple("ebu", TAGS_EBU, EBU_PRIMARIES),
      )
    val CLIP = 300.milliseconds
    val FIRST_CLIP = 100.milliseconds
    val SECOND_CLIP = 400.milliseconds
    val TIMEOUT = 2.minutes
  }
}
