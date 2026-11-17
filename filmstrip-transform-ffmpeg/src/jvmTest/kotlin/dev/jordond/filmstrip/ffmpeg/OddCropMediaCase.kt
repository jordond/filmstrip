package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.applyTo
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration

/**
 * What the odd-crop media tests share: an ffmpeg backend, the clips they export and the frame read back from the file
 * written.
 */
abstract class OddCropMediaCase {
  internal val filmstrip =
    Filmstrip {
      ffmpegBackend()
      filters()
    }

  internal fun compositionOf(
    source: File,
    effects: List<EffectSpec>,
  ): Composition =
    compositionOf {
      clip(MediaSource.of(source.absolutePath)) { effects { effects.forEach { add(it) } } }
    }

  internal fun cutOf(
    frame: Size,
    crop: Crop,
  ): Size = crop.retainedRect(frame).applyTo(frame)

  internal suspend fun exported(
    composition: Composition,
    at: Double = 0.0,
  ): Pair<Written, ExportPlan> {
    val output = File.createTempFile("filmstrip-odd-crop", ".mp4").also { it.delete() }
    val plan =
      when (val verdict = filmstrip.plan(composition, ExportSpec())) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val finished = filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList().last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()
    val size = finished.info.video!!.displaySize
    val frame = frameOf(output, size, at)
    output.delete()
    return Written(size, frame) to plan
  }

  internal class Written(
    val size: Size,
    val rgb: ByteArray,
  ) {
    fun green(
      x: Int,
      y: Int,
    ): Int = rgb[(y * size.width + x) * RGB + 1].toInt() and 0xFF

    fun redness(
      x: Int,
      y: Int,
    ): Int {
      val (red, green, blue) = pixel(x, y)
      return red - maxOf(green, blue)
    }

    private fun pixel(
      x: Int,
      y: Int,
    ): List<Int> = List(RGB) { rgb[(y * size.width + x) * RGB + it].toInt() and 0xFF }

    // An edge line is stray when it differs from the line inside it: the picture and the fill are flat, so a column of
    // chroma padding or of another clip's picture shows against its neighbour.
    fun strayEdges(): List<String> {
      val column = { x: Int -> List(size.height) { pixel(x, it) } }
      val row = { y: Int -> List(size.width) { pixel(it, y) } }
      val edges =
        mapOf(
          "first column" to (column(0) to column(1)),
          "last column" to (column(size.width - 1) to column(size.width - 2)),
          "first row" to (row(0) to row(1)),
          "last row" to (row(size.height - 1) to row(size.height - 2)),
        )
      return edges.mapNotNull { (name, lines) ->
        val (edge, inside) = lines
        val stray = edge.indices.any { i -> edge[i].indices.any { abs(edge[i][it] - inside[i][it]) > EDGE_TOLERANCE } }
        if (stray) "$name differs from the line inside it" else null
      }
    }
  }

  internal fun frameOf(
    file: File,
    size: Size,
    at: Double = 0.0,
  ): ByteArray {
    val command =
      listOf(
        hostFfmpeg,
        "-v",
        "error",
        "-ss",
        at.toString(),
        "-i",
        file.absolutePath,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
      ) +
        listOf("-pix_fmt", "rgb24", "-")
    val process = ProcessBuilder(command).start()
    val frame = process.inputStream.readBytes()
    process.waitFor()
    frame.size shouldBe size.width * size.height * RGB
    return frame
  }

  // The crop keeps a white line on its first and on its last row, so a row lost at the foot leaves the written frame
  // with no white line there. The planner's frame may be a row shorter than the cut, which scales the cut a little.
  internal fun assertMarkedRows(written: Written) {
    val column = written.size.width / 2
    val rows = List(written.size.height) { written.green(column, it) }
    withClue("rows $rows") {
      rows.first() shouldBeGreaterThan WHITE_FLOOR
      rows.last() shouldBeGreaterThan WHITE_FLOOR
      rows[rows.size / 2] shouldBeLessThan WHITE_FLOOR
    }
  }

  internal fun assertMarkedColumns(written: Written) {
    val row = written.size.height / 2
    val columns = List(written.size.width) { written.green(it, row) }
    withClue("columns $columns") {
      columns.first() shouldBeGreaterThan WHITE_FLOOR
      columns.last() shouldBeGreaterThan WHITE_FLOOR
      columns[columns.size / 2] shouldBeLessThan WHITE_FLOOR
    }
  }

  internal fun flatClip(size: Size): File = writeClip(size, "", GRAY_CLIP)

  // A mid gray frame with a white line along the first and last row the clip's crop keeps. The crop is anchored to the
  // centre, so it is the rows or the columns either side of the middle.
  internal fun markedClip(
    size: Size,
    cut: Size,
    across: Boolean = false,
  ): File {
    val markers =
      if (across) {
        val first = ((size.width - cut.width) / 2f).let { (size.width * (it / size.width)).toInt() }
        listOf(first, first + cut.width - 1).joinToString(",") { "drawbox=x=$it:y=0:w=1:h=ih:color=white:t=fill" }
      } else {
        val first = (size.height * ((size.height - cut.height) / 2f / size.height)).toInt()
        listOf(first, first + cut.height - 1).joinToString(",") { "drawbox=x=0:y=$it:w=iw:h=1:color=white:t=fill" }
      }
    return writeClip(size, ",$markers", GRAY_CLIP)
  }

  internal fun writeClip(
    size: Size,
    extra: String,
    color: String,
  ): File {
    val file = File.createTempFile("filmstrip-odd-source", ".mkv").also { it.deleteOnExit() }
    val graph = "color=c=$color:s=${size.width}x${size.height}:r=30:d=1,format=yuv420p$extra"
    val command =
      listOf(hostFfmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", graph) +
        listOf("-c:v", "ffv1", file.absolutePath)
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val log = process.inputStream.readAllBytes().decodeToString()
    withClue("ffmpeg could not write the clip: $log") { (process.waitFor() == 0).shouldBeTrue() }
    return file
  }

  internal companion object {
    const val RGB = 3
    const val SCALED_HEIGHT = 240
    const val SMALL_HEIGHT = 90
    const val CANVAS_HEIGHT = 324
    const val TALL_HEIGHT = 1080
    const val APP_HEIGHT = 320
    const val TALL_APP_HEIGHT = 540
    const val GRAY_CLIP = "gray"
    const val WHITE_CLIP = "white"
    const val RED_CLIP = "red"

    // A lossy encode moves a flat field a few codes, where a line of padding beside a white picture differs by two
    // hundred. A fifth of that step is far above the one and far below the other.
    const val EDGE_TOLERANCE = 40
    const val GRAY_FLOOR = 60
    const val WHITE_FLOOR = 180

    // Red's lead over the other two channels: a saturated red is about two hundred, a neutral pixel none.
    const val RED_FLOOR = 120

    // A frame's time inside the second of two clips a second long.
    const val SECOND_CLIP_AT = 1.5
    val PORTRAIT = Size(180, 320)
    val LANDSCAPE = Size(320, 180)
    val FOUR_THREE = Crop(AspectRatio(4, 3))
  }
}
