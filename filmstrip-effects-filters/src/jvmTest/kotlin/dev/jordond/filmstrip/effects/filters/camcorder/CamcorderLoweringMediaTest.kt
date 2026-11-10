package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The animated PNG a [Camcorder] lowers to on ffmpeg, read back through the host's own APNG demuxer.
 */
class CamcorderLoweringMediaTest {
  @Test
  fun `ffmpeg reads one frame a state at the time each state starts`() {
    val osd = CamcorderOsd(BUSY, NTSC)
    val changes = osd.changesWithin(ZERO, 2.seconds, withFrames = false)

    val frames = decoded(osdAnimation(osd, SMALL, ZERO, 2.seconds), SMALL)

    frames.map { it.first } shouldContainExactly changes.map { it.at.inWholeMicroseconds / UNIT_MICROS }
  }

  @Test
  fun `every frame ffmpeg reads is the raster of its state with the frame counts hidden`() {
    val osd = CamcorderOsd(BUSY, NTSC)
    val changes = osd.changesWithin(ZERO, 2.seconds, withFrames = false)

    val frames = decoded(osdAnimation(osd, SMALL, ZERO, 2.seconds), SMALL)

    frames.zip(changes).forEach { (frame, change) ->
      frame.second.contentEquals(osd.rasterOf(change.state, SMALL.width, SMALL.height).rgbaBytes()) shouldBe true
    }
  }

  @Test
  fun `a day counting frames loops one second of frame counts beside a frame a second`() {
    val osd = CamcorderOsd(Camcorder(listOf(OsdElement.Counter(withFrames = true))), NTSC)

    val sidecars = camcorderFragment(osd, SMALL, ZERO, 24.hours).sidecars

    sidecars.size shouldBe 2
    frameCount(sidecars[1].bytes) shouldBeLessThanOrEqual ceil(NTSC).toInt()
    frameCount(sidecars[0].bytes) shouldBe osd.changesWithin(ZERO, 24.hours, withFrames = false).size
  }

  // The rest of the display is laid over the loop, the way the graph lays it.
  @Test
  fun `the loop and the rest of the display add up to the raster mid second`() {
    val osd = CamcorderOsd(BUSY, NTSC)
    val until = 3.seconds
    val loop = osdFrameLoops(osd, SMALL, ZERO, until).single()
    val counts = decoded(loop.animation, Size(loop.region.width, loop.region.height))
    val rest = decoded(osdAnimation(osd, SMALL, ZERO, until), SMALL)
    val shownAt = { frames: List<Pair<Long, ByteArray>>, units: Long -> frames.last { it.first <= units }.second }

    MID_SECOND_FRAMES.forEach { time ->
      val units = time.inWholeMicroseconds / UNIT_MICROS
      val composed = shownAt(rest, units).copyOf()
      val count = shownAt(counts, units % UNITS_PER_SECOND)
      for (y in 0 until loop.region.height) {
        for (x in 0 until loop.region.width) {
          val from = (y * loop.region.width + x) * RGBA
          val to = ((loop.region.top + y) * SMALL.width + loop.region.left + x) * RGBA
          if (composed[to + 3] == CLEAR) count.copyInto(composed, to, from, from + RGBA)
        }
      }
      withClue("at $time") {
        composed.contentEquals(osd.rasterAt(time, SMALL.width, SMALL.height).rgbaBytes()) shouldBe true
      }
    }
  }

  @Test
  fun `a left corner counter growing an hour digit loops each stretch on its own`() {
    val counter = OsdElement.Counter(start = 9.hours + 59.minutes + 58.seconds, true, OsdCorner.TopLeft)
    val osd = CamcorderOsd(Camcorder(listOf(counter)), NTSC)

    val loops = osdFrameLoops(osd, SMALL, ZERO, 4.seconds)

    loops.map { it.from to it.until } shouldContainExactly listOf(ZERO to 2.seconds, 2.seconds to 4.seconds)
    loops.forEach { frameCount(it.animation) shouldBeLessThanOrEqual ceil(NTSC).toInt() + 2 }
    (loops[0].region == loops[1].region) shouldBe false
  }

  @Test
  fun `a frame count past the frame's edge loops nothing`() {
    val counter = OsdElement.Counter(withFrames = true, corner = OsdCorner.BottomLeft)
    val osd = CamcorderOsd(Camcorder(listOf(counter), textSize = 0.15f), NTSC)

    osdFrameLoops(osd, Size(1080, 1920), ZERO, 2.seconds) shouldBe emptyList()
    camcorderFragment(osd, Size(1080, 1920), ZERO, 2.seconds).sidecars.size shouldBe 1
  }

  @Test
  fun `a state longer than a second is a whole second frame and the rest`() {
    val osd = CamcorderOsd(Camcorder(listOf(OsdElement.Label("SP"))), NTSC)

    val frames = decoded(osdAnimation(osd, SMALL, ZERO, 3_250.milliseconds), SMALL)

    frames.map { it.first } shouldContainExactly listOf(0L, 300_000L)
    frames.last().second.contentEquals(frames.first().second) shouldBe true
  }

  @Test
  fun `an hour of a blinking clock writes one frame a state and not a frame a video frame`() {
    val clock = CamcorderOsd(Camcorder(listOf(OsdElement.Rec(), OsdElement.Clock())), NTSC)

    val animation = osdAnimation(clock, SMALL, ZERO, 1.hours)

    frameCount(animation) shouldBe clock.changesWithin(ZERO, 1.hours).size
  }

  // Each frame the demuxer hands over, with its timestamp in its own units of ten microseconds and its RGBA bytes.
  private fun decoded(
    animation: ByteArray,
    size: Size,
  ): List<Pair<Long, ByteArray>> {
    val file = File.createTempFile("camcorder", ".apng").apply { deleteOnExit() }
    file.writeBytes(animation)
    val shown =
      run(
        listOf(
          hostFfmpeg,
          "-v",
          "info",
          "-f",
          "apng",
          "-max_fps",
          "0",
          "-i",
          file.path,
          "-vf",
          "showinfo",
          "-f",
          "null",
          "-",
        ),
        stderr = true,
      ).decodeToString()
    shown.contains("config in time_base: 1/100000") shouldBe true
    val stamps = Regex("""\] n:\s*\d+ pts:\s*(\d+)""").findAll(shown).map { it.groupValues[1].toLong() }.toList()
    val bytes =
      run(
        listOf(
          hostFfmpeg,
          "-v",
          "error",
          "-f",
          "apng",
          "-max_fps",
          "0",
          "-i",
          file.path,
          "-fps_mode",
          "passthrough",
          "-f",
          "rawvideo",
          "-pix_fmt",
          "rgba",
          "-",
        ),
      )
    val frame = size.width * size.height * RGBA
    return stamps.mapIndexed { index, stamp -> stamp to bytes.copyOfRange(index * frame, (index + 1) * frame) }
  }

  private fun run(
    command: List<String>,
    stderr: Boolean = false,
  ): ByteArray {
    val process = ProcessBuilder(command).redirectErrorStream(stderr).start()
    val out = process.inputStream.readAllBytes()
    process.waitFor() shouldBe 0
    return out
  }

  private fun frameCount(animation: ByteArray): Int {
    val buffer = ByteBuffer.wrap(animation)
    var at = SIGNATURE_BYTES
    while (at < animation.size) {
      val length = buffer.getInt(at)
      val type = animation.copyOfRange(at + 4, at + 8).decodeToString()
      if (type == "acTL") return buffer.getInt(at + 8)
      at += length + CHUNK_OVERHEAD
    }
    error("No acTL chunk")
  }

  private companion object {
    const val NTSC = 30_000f / 1_001f
    const val RGBA = 4
    const val UNIT_MICROS = 10
    const val UNITS_PER_SECOND = 100_000L
    const val CLEAR: Byte = 0

    // Frames 15 and 16 of the third second at 29.97, and one either side of a counter second, which starts at 0.1 s.
    val MID_SECOND_FRAMES =
      listOf(2_500_500.microseconds, 2_534_000.microseconds, 1_099_000.microseconds, 1_101_000.microseconds)
    const val SIGNATURE_BYTES = 8
    const val CHUNK_OVERHEAD = 12
    val ZERO = Duration.ZERO
    val SMALL = Size(160, 120)
    val BUSY =
      Camcorder(
        listOf(
          OsdElement.Rec(),
          OsdElement.Counter(start = 900.milliseconds, withFrames = true),
          OsdElement.Tape(TapeSpeed.LP),
        ),
      )
  }
}
