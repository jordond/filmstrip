package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.color.YcbcrEvaluator
import dev.jordond.filmstrip.color.evaluator
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.PictureAlpha
import dev.jordond.filmstrip.ffmpeg.internal.picture.YuvPlanes
import dev.jordond.filmstrip.ffmpeg.internal.picture.decodedPlanes
import dev.jordond.filmstrip.ffmpeg.internal.picture.yuv4mpegPlanes
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The `yuv4mpegpipe` reader and the plane evaluation the ffmpeg picture decoder lands a picture through, run on y4m
 * streams built here, so no ffmpeg runs.
 *
 * Every expected texel is core's evaluator for BT.709 at the stream's depth, packed the way each sample type stores
 * it. The samples are distinct mid codes, so a plane, texel or byte read out of order lands elsewhere.
 */
class PicturePlanesTest {
  @Test
  fun `an odd sized eight-bit frame is read texel by texel in rows`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val planes = yuv4mpegPlanes(y4m(size, EIGHT, "444", planeSamples(size, EIGHT)), EIGHT).shouldNotBeNull()
    planes.size.width shouldBe ODD_WIDTH
    planes.size.height shouldBe ODD_HEIGHT
    planes.bitDepth shouldBe EIGHT
    assertLanded(planes, planeSamples(size, EIGHT))
  }

  @Test
  fun `ten and twelve bit frames are read as little-endian samples`() {
    for (bitDepth in listOf(TEN, TWELVE)) {
      val size = Size(ODD_WIDTH, ODD_HEIGHT)
      val samples = planeSamples(size, bitDepth)
      val stream = y4m(size, bitDepth, "444p$bitDepth", samples)
      val planes = withClue("$bitDepth bits") { yuv4mpegPlanes(stream, bitDepth).shouldNotBeNull() }
      planes.bitDepth shouldBe bitDepth
      assertLanded(planes, samples)
    }
  }

  @Test
  fun `a frame of another layout or depth is not read`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    yuv4mpegPlanes(y4m(size, EIGHT, "420jpeg", planeSamples(size, EIGHT)), EIGHT).shouldBeNull()
    yuv4mpegPlanes(y4m(size, TEN, "444p10", planeSamples(size, TEN)), TWELVE).shouldBeNull()
    yuv4mpegPlanes(y4m(size, EIGHT, "444", planeSamples(size, EIGHT)), TEN).shouldBeNull()
    yuv4mpegPlanes(y4m(size, TEN, "444p10", planeSamples(size, TEN)), EIGHT).shouldBeNull()
  }

  @Test
  fun `a frame that ends early is not read`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val whole = y4m(size, TEN, "444p10", planeSamples(size, TEN)).readAllBytes()
    yuv4mpegPlanes(ByteArrayInputStream(whole.copyOf(whole.size - 1)), TEN).shouldBeNull()
  }

  @Test
  fun `a header up to 255 characters is read and a longer one is not`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val samples = planeSamples(size, EIGHT)
    val header = "YUV4MPEG2 W$ODD_WIDTH H$ODD_HEIGHT C444 X"
    val longest = header + "x".repeat(MAX_HEADER - 1 - header.length)
    yuv4mpegPlanes(y4m(longest, size, EIGHT, samples), EIGHT).shouldNotBeNull()
    yuv4mpegPlanes(y4m(longest + "x", size, EIGHT, samples), EIGHT).shouldBeNull()
    yuv4mpegPlanes(y4m(header + "x".repeat(OVERLONG), size, EIGHT, samples), EIGHT).shouldBeNull()
  }

  @Test
  fun `tokens the reader does not use are passed over wherever they sit`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val samples = planeSamples(size, TWELVE)
    val header = "YUV4MPEG2 F25:1 W$ODD_WIDTH Ip A1:1 H$ODD_HEIGHT C444p12 XYSCSS=444P12 XCOLORRANGE=FULL"
    val planes = yuv4mpegPlanes(y4m(header, size, TWELVE, samples, frame = "FRAME Ip"), TWELVE).shouldNotBeNull()
    assertLanded(planes, samples)
  }

  @Test
  fun `whatever follows the frame is read to the end`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val frame = y4m(size, EIGHT, "444", planeSamples(size, EIGHT)).readAllBytes()
    val stream = ByteArrayInputStream(frame + "FRAME\n".encodeToByteArray() + ByteArray(TRAILING))
    yuv4mpegPlanes(stream, EIGHT).shouldNotBeNull()
    stream.read() shouldBe -1
  }

  @Test
  fun `eight-bit planes pack as opaque ARGB and deeper ones as opaque sixteen-bit RGBA`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val narrow = decodedPlanes(planesOf(size, EIGHT), evaluator(EIGHT)).samples
    narrow.shouldBeInstanceOf<StoredSamples.Argb>()
    val wide = decodedPlanes(planesOf(size, TEN), evaluator(TEN)).samples
    val rgba = wide.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
    rgba.size shouldBe size.width * size.height * CHANNELS
  }

  @Test
  fun `an interrupted thread stops the evaluation and has its interrupt cleared`() {
    val planes = planesOf(Size(ODD_WIDTH, ODD_HEIGHT), TWELVE)
    Thread.currentThread().interrupt()
    try {
      assertFailsWith<InterruptedException> { decodedPlanes(planes, evaluator(TWELVE)) }
      Thread.currentThread().isInterrupted.shouldBeFalse()
    } finally {
      Thread.interrupted()
    }
  }

  // The worker holds at the middle row until the interrupt has landed, so the row it stops at is known rather than
  // however far it got during a fixed sleep.
  @Test
  fun `an interrupt from another thread stops the evaluation part way through the picture`() {
    val size = Size(STOP_SIDE, STOP_SIDE)
    val planes = YuvPlanes(size, EIGHT, List(3) { ByteArray(size.width * size.height) { MID.toByte() } })
    val reached = CountDownLatch(1)
    val stopped = AtomicReference<Throwable?>()
    val holdAtStop = { row: Int ->
      if (row == STOP_ROW) {
        reached.countDown()
        val deadline = System.nanoTime() + HOLD_LIMIT_NANOS
        while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) Thread.onSpinWait()
      }
    }
    val worker =
      Thread {
        stopped.set(runCatching { decodedPlanes(planes, evaluator(EIGHT), beforeRow = holdAtStop) }.exceptionOrNull())
      }
    worker.start()
    reached.await()
    worker.interrupt()
    worker.join()

    val interrupted =
      withClue("the evaluation ran to its end") {
        stopped.get().shouldBeInstanceOf<InterruptedException>()
      }
    val row =
      ROW
        .find(interrupted.message.orEmpty())
        .shouldNotBeNull()
        .groupValues[1]
        .toInt()
    withClue("stopped at row $row of ${size.height}") { row shouldBe STOP_ROW }
  }

  @Test
  fun `each texel takes its alpha from the plane and a premultiplied color comes back straight`() {
    val size = Size(ODD_WIDTH, ODD_HEIGHT)
    val planes = planesOf(size, TEN)
    val codes = IntArray(size.width * size.height) { ALPHA_FIRST + it * ALPHA_STEP }
    val evaluator = evaluator(TEN)
    for (premultiplied in listOf(false, true)) {
      val decoded = decodedPlanes(planes, evaluator, PictureAlpha(size, codes, premultiplied))
      val rgba = decoded.samples.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
      val (y, cb, cr) = planeSamples(size, TEN)
      for (at in codes.indices) {
        val alpha = codes[at] / WIDE.toDouble()
        val rgb = evaluator.rgb(y[at], cb[at], cr[at]).map { if (premultiplied) minOf(1.0, it / alpha) else it }
        val expected = rgb.map { (it * WIDE).roundToInt() } + codes[at]
        val read = (0 until CHANNELS).map { rgba[at * CHANNELS + it].toInt() and WIDE }
        withClue("texel $at, premultiplied $premultiplied") { read shouldBe expected }
      }
    }
  }

  // [planes] evaluated and checked texel by texel against core's evaluator of [samples], the three planes as codes.
  private fun assertLanded(
    planes: YuvPlanes,
    samples: List<IntArray>,
  ) {
    val evaluator = evaluator(planes.bitDepth)
    val decoded = decodedPlanes(planes, evaluator)
    decoded.color.shouldBeNull()
    val (y, cb, cr) = samples
    for (at in y.indices) {
      val rgb = evaluator.rgb(y[at], cb[at], cr[at])
      when (val stored = decoded.samples) {
        is StoredSamples.Argb -> {
          val codes = rgb.map { (it * BYTE).roundToInt() }
          val packed = (BYTE shl ALPHA_SHIFT) or (codes[0] shl RED_SHIFT) or (codes[1] shl GREEN_SHIFT) or codes[2]
          withClue("texel $at") { stored.argb[at] shouldBe packed }
        }
        is StoredSamples.Rgba16 -> {
          val expected = rgb.map { (it * WIDE).roundToInt() } + WIDE
          val read = (0 until CHANNELS).map { stored.rgba[at * CHANNELS + it].toInt() and WIDE }
          withClue("texel $at") { read shouldBe expected }
        }
      }
    }
  }

  private fun evaluator(bitDepth: Int): YcbcrEvaluator =
    ycbcrReading(BT709, BT709, SRGB_TRANSFER).evaluator(bitDepth, fullRange = false)

  private fun planesOf(
    size: Size,
    bitDepth: Int,
  ): YuvPlanes = YuvPlanes(size, bitDepth, planeSamples(size, bitDepth).map { it.bytes(bitDepth) })

  private fun y4m(
    size: Size,
    bitDepth: Int,
    layout: String,
    samples: List<IntArray>,
  ): ByteArrayInputStream = y4m("YUV4MPEG2 W${size.width} H${size.height} C$layout", size, bitDepth, samples)

  private fun y4m(
    header: String,
    size: Size,
    bitDepth: Int,
    samples: List<IntArray>,
    frame: String = "FRAME",
  ): ByteArrayInputStream {
    require(samples.all { it.size == size.width * size.height })
    val body = samples.map { it.bytes(bitDepth) }.reduce(ByteArray::plus)
    return ByteArrayInputStream("$header\n$frame\n".encodeToByteArray() + body)
  }

  // Three planes of distinct codes inside the limited range at [bitDepth], luma rising and chroma crossing it.
  private fun planeSamples(
    size: Size,
    bitDepth: Int,
  ): List<IntArray> {
    val scale = 1 shl (bitDepth - EIGHT)
    val texels = size.width * size.height
    return listOf(
      IntArray(texels) { (LUMA_FIRST + it * STEP) * scale + it % scale },
      IntArray(texels) { (CHROMA_FIRST + it * STEP) * scale + (scale - 1 - it % scale) },
      IntArray(texels) { (CHROMA_LAST - it * STEP) * scale + it % scale },
    )
  }

  // [this] as one byte a code at eight bits and two little-endian bytes a code past that.
  private fun IntArray.bytes(bitDepth: Int): ByteArray =
    if (bitDepth == EIGHT) {
      ByteArray(size) { this[it].toByte() }
    } else {
      ByteArray(size * 2) { if (it % 2 == 0) this[it / 2].toByte() else (this[it / 2] shr GREEN_SHIFT).toByte() }
    }

  private companion object {
    const val EIGHT = 8
    const val TEN = 10
    const val TWELVE = 12
    const val ODD_WIDTH = 3
    const val ODD_HEIGHT = 5
    const val MAX_HEADER = 256
    const val OVERLONG = 300
    const val TRAILING = 64
    const val LUMA_FIRST = 40
    const val CHROMA_FIRST = 60
    const val CHROMA_LAST = 200
    const val STEP = 9
    const val CHANNELS = 4
    const val BYTE = 0xFF
    const val WIDE = 0xFFFF
    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val BT709 = 1
    const val SRGB_TRANSFER = 13
    const val STOP_SIDE = 64
    const val STOP_ROW = 32
    const val HOLD_LIMIT_NANOS = 10_000_000_000L
    const val MID = 128
    const val ALPHA_FIRST = 20_000
    const val ALPHA_STEP = 1_500
    val ROW = Regex("at row (\\d+)")
  }
}
