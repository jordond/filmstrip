package dev.jordond.filmstrip.effects.filters.crt

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * Reads [CrtFrames] and what ffmpeg makes of them against [CrtSignal], with no conversion to 4:2:0 in the way.
 *
 * The ffmpeg reads run [hostFfmpeg], the one the build declares as this suite's input.
 */
class CrtLoweringMediaTest {
  @Test
  fun `every static frame holds the signal at a mid intensity`() {
    val frame = Size(320, 180)
    val params = CrtParams.at(MID)
    val frames = CrtFrames.of(params, frame)

    frames.sidecars shouldHaveSize FRAME_COUNT
    val left = Netpbm(frames.leftColumn.bytes)
    val right = Netpbm(frames.rightColumn.bytes)
    val top = Netpbm(frames.topRow.bytes)
    val bottom = Netpbm(frames.bottomRow.bytes)
    val across = Netpbm(frames.across.bytes)
    val down = Netpbm(frames.down.bytes)
    val multiplier = Netpbm(frames.multiplier.bytes)
    val coverage = Netpbm(frames.coverage.bytes)

    for (y in 0 until frame.height step STRIDE) {
      for (x in 0 until frame.width step STRIDE) {
        val u = (x + 0.5) / frame.width
        val v = (y + 0.5) / frame.height
        val source = CrtSignal.barrelMap(u, v, frame.width, frame.height, params.barrelK)
        val column = source[0] * frame.width - 0.5
        val row = source[1] * frame.height - 0.5
        withClue("$x $y") {
          left.code(x, y) shouldBe floor(column).toInt().coerceIn(0, frame.width - 1)
          right.code(x, y) shouldBe (floor(column).toInt() + 1).coerceIn(0, frame.width - 1)
          top.code(x, y) shouldBe floor(row).toInt().coerceIn(0, frame.height - 1)
          bottom.code(x, y) shouldBe (floor(row).toInt() + 1).coerceIn(0, frame.height - 1)
          abs(across.share(x, y) - (column - floor(column))) shouldBeLessThan ONE_CODE
          abs(down.share(x, y) - (row - floor(row))) shouldBeLessThan ONE_CODE
          val covered = CrtSignal.coverageAt(u, v, frame.width, frame.height, params.barrelK)
          abs(coverage.share(x, y) - covered) shouldBeLessThan ONE_CODE
          for (channel in 0 until RGB) {
            val wanted = CrtSignal.multiplierAt(u, v, frame.width, frame.height, params, channel)
            abs(multiplier.share(x, y, channel) * params.gain - wanted) shouldBeLessThan ONE_CODE * params.gain
          }
        }
      }
    }
  }

  @Test
  fun `clips of one size and strength share a fragment and another size gets its own`() {
    val crt = Crt(MID.toFloat())
    val small = crtFragment(crt, Size(64, 36))

    crtFragment(crt, Size(64, 36)) shouldBeSameInstanceAs small
    (crtFragment(crt, Size(36, 36)).sidecars == small.sidecars) shouldBe false
    (crtFragment(Crt(1f), Size(64, 36)).sidecars == small.sidecars) shouldBe false
  }

  @Test
  fun `every channel lands on the signal at each size with a mid intensity`() {
    listOf(Size(640, 360), Size(1366, 768), Size(360, 640)).forEach { frame ->
      val params = CrtParams.at(MID)
      val read = drawn(params, frame, PICTURE, OPAQUE)
      var moved = 0.0
      readsOf(frame).forEach { (x, y) ->
        val wanted = wanted(frame, params, x, y, PICTURE)
        for (channel in 0 until RGB) {
          withClue("${frame.width} by ${frame.height} at $x $y channel $channel") {
            abs(read.share(frame, x, y, channel) - wanted[channel]) shouldBeLessThan DRAWN_SHARE
          }
          moved = max(moved, abs(read.share(frame, x, y, channel) - PICTURE[channel]))
        }
      }
      withClue("the look has to move the frame well past the tolerance for this to tell") {
        moved shouldBeGreaterThan DRAWN_SHARE * 4
      }
    }
  }

  @Test
  fun `the gain stops at a premultiplied pixels own alpha and alpha holds past the curve`() {
    val frame = Size(1280, 720)
    val params = CrtParams.at(1.0)

    val read = drawn(params, frame, CLAMPING_PICTURE, CLAMPING_ALPHA)
    withClue("the gain has to reach full scale somewhere for this to tell") {
      val reached =
        readsOf(frame).maxOf { (x, y) ->
          val u = (x + 0.5) / frame.width
          val v = (y + 0.5) / frame.height
          CrtSignal.multiplierAt(u, v, frame.width, frame.height, params, 0) * CLAMPING_PICTURE[0]
        }
      reached shouldBeGreaterThan 1.0
    }

    readsOf(frame).forEach { (x, y) ->
      val wanted = wanted(frame, params, x, y, CLAMPING_PICTURE)
      for (channel in 0 until RGB) {
        withClue("at $x $y channel $channel") {
          abs(read.share(frame, x, y, channel) - wanted[channel]) shouldBeLessThan DRAWN_SHARE
        }
      }
      withClue("alpha at $x $y") {
        abs(read.share(frame, x, y, ALPHA_PLANE) - CLAMPING_ALPHA) shouldBeLessThan ONE_CODE * 2
      }
    }
  }

  @Test
  fun `the way down to eight bits lands every code on itself on every plane`() {
    val empty = emptyMap<String, String>()
    val down = CRT_TO_EIGHT_BITS.joinToString(",") { it.rendered(empty) }
    val coded = "X*257"

    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${CODES}x1:d=0.04",
        "-frames:v",
        "1",
        "-vf",
        "format=gbrap16le,geq=r=$coded:g=$coded:b=$coded:a=$coded,$down",
        "-f",
        "rawvideo",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val read = process.inputStream.readBytes()
    process.waitFor() shouldBe 0

    read.size shouldBe CODES * PLANES
    read.forEachIndexed { at, code -> withClue("sample $at") { (code.toInt() and 0xFF) shouldBe at % CODES } }
  }

  private fun wanted(
    frame: Size,
    params: CrtParams,
    x: Int,
    y: Int,
    picture: DoubleArray,
  ): DoubleArray =
    CrtSignal.pixelAt(
      (x + 0.5) / frame.width,
      (y + 0.5) / frame.height,
      frame.width,
      frame.height,
      params,
    ) { _, _ -> picture }

  // The rows and columns through the middle of each side, where the curve's edge crosses, and a spread over the frame.
  private fun readsOf(frame: Size): List<Pair<Int, Int>> {
    val spreadX = (0 until frame.width step frame.width / SPREAD).toList()
    val spreadY = (0 until frame.height step frame.height / SPREAD).toList()
    return spreadY.flatMap { y -> (0 until frame.width step EDGE_STRIDE).map { it to y } } +
      spreadX.flatMap { x -> (0 until frame.height step EDGE_STRIDE).map { x to it } }
  }

  // Runs crtTube on a flat premultiplied frame, and hands back straight colour and alpha as ffmpeg's own planes.
  private fun drawn(
    params: CrtParams,
    frame: Size,
    picture: DoubleArray,
    alpha: Double,
  ): ByteArray {
    val frames = CrtFrames.of(params, frame)
    val directory = File.createTempFile("crt-lowering", "").apply { delete() }
    directory.mkdirs()
    try {
      val paths =
        frames.sidecars.withIndex().associate { (index, sidecar) ->
          val file = File(directory, "frame$index.${sidecar.extension}")
          file.writeBytes(sidecar.bytes)
          sidecar.placeholder to file.path
        }
      val flat =
        "color=c=black:s=${frame.width}x${frame.height}:d=0.04,format=gbrap16le," +
          "geq=r=${picture[0] * FULL_CODE}:g=${picture[1] * FULL_CODE}:b=${picture[2] * FULL_CODE}:" +
          "a=${alpha * FULL_CODE},premultiply=inplace=1[$TUBE_READ]"
      val graph =
        (listOf(flat) + crtTube(params, frames).map { it.rendered(paths) } + "[$TUBE_WRITE]null[done]")
          .joinToString(";")
      val process =
        ProcessBuilder(
          hostFfmpeg,
          "-hide_banner",
          "-loglevel",
          "error",
          "-filter_complex",
          graph,
          "-map",
          "[done]",
          "-frames:v",
          "1",
          "-f",
          "rawvideo",
          "-pix_fmt",
          "gbrap16le",
          "-",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
      val out = ByteArrayOutputStream()
      process.inputStream.copyTo(out)
      process.waitFor() shouldBe 0
      return out.toByteArray()
    } finally {
      directory.deleteRecursively()
    }
  }

  private fun FilterBranch.rendered(paths: Map<String, String>): String {
    val reading = reads.joinToString("") { "[$it]" }
    val writing = writes.joinToString("") { "[$it]" }
    if (nodes.isEmpty()) return "${reading}split=${writes.size}$writing"
    return reading + nodes.joinToString(",") { it.rendered(paths) } + writing
  }

  private fun FilterNode.rendered(paths: Map<String, String>): String =
    if (arguments.isEmpty()) {
      name
    } else {
      name + "=" + arguments.joinToString(":") { "${it.key}='${paths[it.value] ?: it.value}'" }
    }

  // gbrap16le lays out green, blue, red and alpha.
  private fun ByteArray.share(
    frame: Size,
    x: Int,
    y: Int,
    channel: Int,
  ): Double {
    val plane = PLANE_OF_CHANNEL[channel]
    val at = (plane * frame.width * frame.height + y * frame.width + x) * 2
    return ((this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)) / FULL_CODE.toDouble()
  }

  private class Netpbm(
    private val bytes: ByteArray,
  ) {
    private val channels = if (bytes[1] == '5'.code.toByte()) 1 else RGB
    private val width: Int
    private val start: Int

    init {
      val header = bytes.copyOf(HEADER_READ).decodeToString().split('\n', limit = HEADER_LINES + 1)
      width = header[1].substringBefore(' ').toInt()
      start = header.take(HEADER_LINES).sumOf { it.length + 1 }
    }

    fun code(
      x: Int,
      y: Int,
      channel: Int = 0,
    ): Int {
      val at = start + ((y * width + x) * channels + channel) * 2
      return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }

    fun share(
      x: Int,
      y: Int,
      channel: Int = 0,
    ): Double = code(x, y, channel) / FULL_CODE.toDouble()
  }

  private companion object {
    const val MID = 0.5
    const val RGB = 3
    const val FRAME_COUNT = 8
    const val CODES = 256
    const val PLANES = 4
    const val STRIDE = 7
    const val SPREAD = 6
    const val EDGE_STRIDE = 3
    const val OPAQUE = 1.0
    const val CLAMPING_ALPHA = 0x90 / 255.0
    const val ALPHA_PLANE = 3
    const val HEADER_READ = 32
    const val HEADER_LINES = 3
    val PICTURE = doubleArrayOf(0x60 / 255.0, 0x80 / 255.0, 0xA0 / 255.0)
    val CLAMPING_PICTURE = doubleArrayOf(1.0, 0.9, 0.95)
    val PLANE_OF_CHANNEL = intArrayOf(2, 0, 1, 3)

    // How close a code in a frame sits to its share, a rounding either side.
    const val ONE_CODE = 1.0 / FULL_CODE

    // Each merge and blend rounds a code or two of sixteen bits, so a frame stays well under one code of eight.
    const val DRAWN_SHARE = 0.002
  }
}
