package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_BITRATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.SheetReads
import dev.jordond.filmstrip.ffmpeg.support.SheetReads.RGB
import dev.jordond.filmstrip.ffmpeg.support.drawnRect
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import dev.jordond.filmstrip.test.AnimatedWebps
import dev.jordond.filmstrip.test.CounterCells
import dev.jordond.filmstrip.test.LossyWebpStills
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Animated GIF, APNG and WebP overlays exported through a real ffmpeg, with the frame on every written slot read back
 * and compared with the one the file's own timeline puts there.
 *
 * Every frame of a counter file writes its index as eight squares, so a slot is read by what the overlay shows and
 * never by a color an encoder moves.
 */
class FfmpegAnimatedPictureMediaTest {
  private val fixture = FfmpegExportFixture()
  private val root: File = createTempDirectory("filmstrip-animated").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `every container shows the timeline's frame on every slot at every rate`() =
    runTest(timeout = TIMEOUT) {
      val canvas = AnimatedPictures.COUNTER_CANVAS
      val ops = AnimatedPictures.counterOps()
      val files =
        listOf(
          "gif" to AnimatedGifs.bytes(canvas, ops, loops = 0),
          "apng" to AnimatedApngs.bytes(canvas, ops),
          "webp" to AnimatedWebps.bytes(canvas, ops),
        )
      for ((name, bytes) in files) {
        for (rate in RATES) {
          withClue("$name at $rate fps") { assertCounters(overlayOf(bytes), rate) }
        }
      }
    }

  @Test
  fun `a clip at speed two counts the frames on the output slots`() =
    runTest(timeout = TIMEOUT) {
      val bytes = AnimatedGifs.bytes(AnimatedPictures.COUNTER_CANVAS, AnimatedPictures.counterOps(), loops = 0)
      val spec = overlayOf(bytes)
      for (rate in listOf(30, 60)) {
        assertCounters(spec, rate, source = SheetReads.backdrop(30, CLIP_LENGTH * 2)) { source ->
          compositionOf {
            clip(MediaSource.of(source.absolutePath)) { speed(2.0) }
            effects { add(spec) }
          }
        }
      }
    }

  // A frame is compared texel by texel against the painter's canvas, in the middle of the first play, of the second
  // and of the hold on the last frame. The pictures play twice at 40 ms a frame, so the clip outlasts them.
  @Test
  fun `a hostile file draws the canvas the painter says on a slot in each play`() =
    runTest(timeout = TIMEOUT) {
      val gif = AnimatedGifs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.WITH_PREVIOUS, loops = 2)
      val apng = AnimatedApngs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.WITH_SOURCE, plays = 2)
      val webp = AnimatedWebps.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED, loops = 2)
      val lossy = AnimatedWebps.bytes(AnimatedPictures.CANVAS, AnimatedPictures.LOSSY_FRAMES, loops = 2)
      listOf(
        Triple("gif", gif, AnimatedPictures.WITH_PREVIOUS),
        Triple("apng", apng, AnimatedPictures.WITH_SOURCE),
        Triple("webp", webp, AnimatedPictures.SHARED),
        Triple("lossy webp", lossy, AnimatedPictures.LOSSY_FRAMES),
      ).forEach { (name, bytes, ops) -> withClue(name) { assertCanvases(bytes, ops) } }
    }

  @Test
  fun `an animated picture on a kept PQ grade shows the timeline's frame mid run`() =
    runTest(timeout = TIMEOUT) {
      val bytes = AnimatedGifs.bytes(AnimatedPictures.COUNTER_CANVAS, AnimatedPictures.counterOps(), loops = 0)
      val spec = overlayOf(bytes)
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.hdr.absolutePath))
          effects { add(spec) }
        }
      val plan =
        fixture.plannedRun(
          composition,
          ExportSpec(hdr = HdrMode.KeepHdr, bitrate = COLOR_READ_BITRATE, frameRate = 30),
        )
      val output = File(root, "pq.mp4")
      fixture.exported(plan, output)
      val size = plan.output.size
      val span = TimeRange.of(Duration.ZERO, plan.duration)
      val timeline = timelineOf(bytes)
      val start = spec.drawnRangeWithin(span).start
      var read = 0
      SheetReads.forEachLuma(output.absolutePath, size, bytesPerSample = 2) { slot, luma ->
        val rect = drawnRect(spec, size, span, outputSlotTime(slot.toLong(), 30), AnimatedPictures.COUNTER_CANVAS)
        val cell = cellOf(rect, size) { x, y -> luma16(luma, y * size.width + x) }
        withClue("slot $slot on the PQ grade") {
          CounterCells.indexOf(snapped(cell, PQ_DARK, PQ_LIGHT)) shouldBe timeline.frameOnSlot(start, slot.toLong(), 30)
        }
        read++
      }
      withClue("frames read off the PQ export") { (read > 0) shouldBe true }
    }

  @Test
  fun `one lowering of a WebP on two overlays spawns one ffmpeg child for all its frames`() {
    val bytes = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, AnimatedPictures.counterOps(), loops = 0)
    val file = File(root, "counters.webp").apply { writeBytes(bytes) }
    val spawned = SpawnLog()
    val a = ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SCALE)
    val b = ImageOverlay(ImageSource.of(file.path), Corner.BottomEnd, scale = SCALE)
    val composition =
      compositionOf {
        clip(MediaSource.of("clip.mp4"))
        effects {
          add(a)
          add(b)
        }
      }
    val resolver = BuiltInEffectResolver(FfmpegPictureDecoder(spawned.ffmpeg))

    kotlinx.coroutines.runBlocking { resolver.prepare(composition) }
    val started = System.nanoTime()
    resolver.resolve(a, CAPABILITIES, attributes()).shouldBeInstanceOf<EffectResolution.Resolved>()
    val planned = (System.nanoTime() - started) / NANOS_PER_MILLI
    resolver.resolve(b, CAPABILITIES, attributes()).shouldBeInstanceOf<EffectResolution.Resolved>()

    println("plan time for the 60 frame WebP, first overlay: $planned ms")
    spawned.count() shouldBe 1
  }

  @Test
  fun `a GIF and an APNG are drawn from core's frames and spawn no ffmpeg child`() {
    val canvas = AnimatedPictures.COUNTER_CANVAS
    val ops = AnimatedPictures.counterOps()
    for (bytes in listOf(AnimatedGifs.bytes(canvas, ops, loops = 0), AnimatedApngs.bytes(canvas, ops))) {
      val spawned = SpawnLog()
      val spec = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart, scale = SCALE)
      BuiltInEffectResolver(FfmpegPictureDecoder(spawned.ffmpeg))
        .resolve(spec, CAPABILITIES, attributes())
        .shouldBeInstanceOf<EffectResolution.Resolved>()
      spawned.count() shouldBe 0
    }
  }

  @Test
  fun `an animated AVIF is refused by name`() {
    val avif =
      byteArrayOf(0, 0, 0, 0x18) + "ftypavis".encodeToByteArray() + ByteArray(4) + "avismif1".encodeToByteArray()
    val spec = ImageOverlay(ImageSource.ofBytes(avif), Corner.TopStart)

    val refused =
      BuiltInEffectResolver(FfmpegPictureDecoder(hostFfmpeg))
        .resolve(spec, CAPABILITIES, attributes())
        .shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldContain ANIMATED_AVIF_REFUSED
  }

  @Test
  fun `a picture whose frames pass the ceiling is refused by name`() {
    val huge = Size(HUGE_SIDE, HUGE_SIDE)
    val ops = listOf(Op(0, 0, AnimatedPictures.Tiles.GREEN), Op(0, 0, AnimatedPictures.Tiles.RED))
    val spec = ImageOverlay(ImageSource.ofBytes(AnimatedGifs.bytes(huge, ops, loops = 0)), Corner.TopStart)

    val refused =
      BuiltInEffectResolver(FfmpegPictureDecoder(hostFfmpeg))
        .resolve(spec, CAPABILITIES, attributes())
        .shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldContain "an overlay may hold"
  }

  // The straight samples of a lossy still are what dwebp writes for its planes, on every parity of width and height and
  // with the last column of an odd width included.
  @Test
  fun `every lossy WebP still decodes to its dwebp truth exactly`() {
    val decoder = FfmpegPictureDecoder(hostFfmpeg)
    for (still in LossyWebpStills.ALL) {
      val bytes = AnimatedWebps.still(still.frameChunks(), still.width, still.height)
      val decoded =
        decoder
          .decode(
            ImageSource.ofBytes(bytes),
          ).shouldBeInstanceOf<OverlayPictureRead.Read<DecodedPicture>>()
      val argb =
        decoded.value.samples
          .shouldBeInstanceOf<StoredSamples.Argb>()
          .argb
      withClue("${still.name} is ${still.width} by ${still.height}") {
        argb.toList() shouldBe packedTruth(still.truth()).toList()
      }
    }
  }

  @Test
  fun `a plain lossy WebP still overlay draws the texels dwebp writes`() {
    for (still in LossyWebpStills.ALL) {
      val bytes = AnimatedWebps.still(still.frameChunks(), still.width, still.height)
      val spec = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart, scale = SCALE)
      val fragment =
        BuiltInEffectResolver(FfmpegPictureDecoder(hostFfmpeg))
          .resolve(spec, CAPABILITIES, attributes())
          .shouldBeInstanceOf<EffectResolution.Resolved>()
          .effect.fragment
      val drawn =
        fragment.auxInputs
          .single()
          .image
          .shouldBeInstanceOf<ImageSource.Bytes>()
          .bytes
      val image = ImageIO.read(ByteArrayInputStream(drawn))
      val read = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
      withClue(still.name) { read.toList() shouldBe packedTruth(still.truth()).toList() }
    }
  }

  private fun packedTruth(rgba: ByteArray): IntArray =
    IntArray(rgba.size / 4) {
      val at = it * 4
      ((rgba[at + 3].toInt() and 0xFF) shl 24) or ((rgba[at].toInt() and 0xFF) shl 16) or
        ((rgba[at + 1].toInt() and 0xFF) shl 8) or (rgba[at + 2].toInt() and 0xFF)
    }

  private fun attributes(): Attributes =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = 30f,
      span = TimeRange.of(Duration.ZERO, CLIP_LENGTH),
    )

  private fun overlayOf(bytes: ByteArray): ImageOverlay =
    ImageOverlay(image = ImageSource.ofBytes(bytes), corner = Corner.TopStart, margin = 0f, scale = SCALE)

  private fun timelineOf(bytes: ByteArray): FrameTimeline =
    animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>().picture.timeline

  /**
   * Exports [spec] at [rate] and reads the counter off every written slot, which must be the frame the file's timeline
   * puts on it.
   */
  private suspend fun assertCounters(
    spec: ImageOverlay,
    rate: Int,
    source: File = SheetReads.backdrop(SOURCE_RATE, CLIP_LENGTH),
    build: (File) -> Composition = {
      compositionOf {
        clip(MediaSource.of(it.absolutePath))
        effects { add(spec) }
      }
    },
  ) {
    val output = File(root, "counters-$rate-${System.nanoTime()}.mp4")
    val plan = fixture.plannedRun(build(source), ExportSpec(frameRate = rate))
    fixture.exported(plan, output)
    source.delete()
    val size = plan.output.size
    val span = TimeRange.of(Duration.ZERO, plan.duration)
    val timeline = timelineOf((spec.image as ImageSource.Bytes).bytes)
    val start = spec.drawnRangeWithin(span).start
    val seen = mutableSetOf<Int>()
    SheetReads.forEachFrame(output.absolutePath, size) { slot, rgb ->
      val texel = SheetReads.packed(rgb, size.width, RGB)
      val rect = drawnRect(spec, size, span, outputSlotTime(slot.toLong(), rate), AnimatedPictures.COUNTER_CANVAS)
      val read = CounterCells.indexOf(snapped(cellOf(rect, size, texel), DARK, LIGHT, luma = ::luma))
      val expected = timeline.frameOnSlot(start, slot.toLong(), rate)
      withClue("slot $slot at $rate fps read $read, the timeline says $expected") { read shouldBe expected }
      seen += read
    }
    withClue("the clip runs past a whole play, so nearly every frame is read once, and a rate of 24 skips some") {
      (seen.size >= MOST_FRAMES) shouldBe true
    }
    output.delete()
  }

  // Each texel of [spec]'s overlay at [canvas] size compared with the painter's, on a slot of each play. The overlay's
  // own backdrop is flat, so a texel the painter leaves clear reads as the backdrop's own color on that frame.
  private suspend fun assertCanvases(
    bytes: ByteArray,
    ops: List<Op>,
  ) {
    val spec = overlayOf(bytes)
    val source = SheetReads.backdrop(SOURCE_RATE, CLIP_LENGTH)
    val output = File(root, "hostile-${System.nanoTime()}.mp4")
    val plan =
      fixture.plannedRun(
        compositionOf {
          clip(MediaSource.of(source.absolutePath))
          effects { add(spec) }
        },
        ExportSpec(frameRate = SOURCE_RATE, bitrate = COLOR_READ_BITRATE),
      )
    fixture.exported(plan, output)
    source.delete()
    val size = plan.output.size
    val span = TimeRange.of(Duration.ZERO, plan.duration)
    val timeline = timelineOf(bytes)
    val start = spec.drawnRangeWithin(span).start
    val canvases = AnimatedPictures.canvases(AnimatedPictures.CANVAS, ops)
    val shown = mutableSetOf<Int>()
    var widest = 0
    SheetReads.forEachFrame(output.absolutePath, size) { slot, rgb ->
      val frame = timeline.frameOnSlot(start, slot.toLong(), SOURCE_RATE)
      shown += frame
      val texel = SheetReads.packed(rgb, size.width, RGB)
      val rect = drawnRect(spec, size, span, outputSlotTime(slot.toLong(), SOURCE_RATE), AnimatedPictures.CANVAS)
      val under = texel(size.width - 1, size.height - 1)
      val painted = canvases[frame]
      val worst = worstGap(painted, AnimatedPictures.CANVAS, rect, size, texel, under)
      widest = maxOf(widest, worst)
      withClue("slot $slot, frame $frame, the worst interior texel is $worst codes off") {
        worst shouldBeLessThanOrEqual PICTURE_TOLERANCE
      }
    }
    println("worst interior texel over every slot: $widest codes")
    withClue("every frame of the file is shown") { shown.size shouldBe ops.size }
    output.delete()
  }

  // The largest channel gap between each interior texel of [painted] laid over [under] and the pixel read at its
  // middle. A texel near a different one shares chroma with it in a 4:2:0 export and takes the scaler's blur, so only a
  // texel whose 24 neighbours two out match it is read.
  private fun worstGap(
    painted: IntArray,
    canvas: Size,
    rect: NormalizedRect,
    frame: Size,
    texels: (Int, Int) -> Int,
    under: Int,
  ): Int {
    var worst = 0
    for (y in 2 until canvas.height - 2) {
      for (x in 2 until canvas.width - 2) {
        val here = painted[y * canvas.width + x]
        val flat = (-2..2).all { dy -> (-2..2).all { dx -> painted[(y + dy) * canvas.width + x + dx] == here } }
        if (!flat) continue
        val px = ((rect.left + rect.width * (x + 0.5f) / canvas.width) * frame.width).toInt()
        val py = ((rect.top + rect.height * (y + 0.5f) / canvas.height) * frame.height).toInt()
        val read = texels(px, py)
        val alpha = here ushr 24
        for (shift in intArrayOf(16, 8, 0)) {
          val over = (here shr shift) and 0xFF
          val below = (under shr shift) and 0xFF
          val expected = (over * alpha + below * (255 - alpha) + 127) / 255
          worst = maxOf(worst, abs(expected - ((read shr shift) and 0xFF)))
        }
      }
    }
    return worst
  }

  // The counter cell of [rect], 32 by 16 texels, each read a texel's width right and down of its own middle, so the
  // top left texel of a square the counter writes reads the middle of the square, clear of the scaler's blur.
  private fun cellOf(
    rect: NormalizedRect,
    frame: Size,
    read: (Int, Int) -> Int,
  ): IntArray {
    val cell = AnimatedPictures.COUNTER_CELL
    val canvas = AnimatedPictures.COUNTER_CANVAS
    return IntArray(cell.width * cell.height) {
      val x = ((rect.left + rect.width * (it % cell.width + 1f) / canvas.width) * frame.width).toInt()
      val y = ((rect.top + rect.height * (it / cell.width + 1f) / canvas.height) * frame.height).toInt()
      read(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
    }
  }

  private fun snapped(
    cell: IntArray,
    dark: Int,
    light: Int,
    luma: (Int) -> Int = { it },
  ): IntArray =
    IntArray(cell.size) {
      val level = luma(cell[it])
      when {
        level < dark -> BLACK
        level > light -> WHITE
        else -> cell[it]
      }
    }

  private fun luma(argb: Int): Int = (((argb shr 16) and 0xFF) * 3 + ((argb shr 8) and 0xFF) * 6 + (argb and 0xFF)) / 10

  private fun luma16(
    plane: ByteArray,
    index: Int,
  ): Int = (plane[index * 2].toInt() and 0xFF) or ((plane[index * 2 + 1].toInt() and 0xFF) shl 8)

  // Hands each child it spawns to the real ffmpeg and counts the starts, so a count is of processes and not of calls.
  private inner class SpawnLog {
    private val log = File(root, "spawns-${System.nanoTime()}.log")
    val ffmpeg: String

    init {
      val script = File(root, "ffmpeg-${System.nanoTime()}.sh")
      script.writeText("#!/bin/sh\necho x >> '${log.absolutePath}'\nexec '$hostFfmpeg' \"\$@\"\n")
      script.setExecutable(true)
      ffmpeg = script.absolutePath
    }

    fun count(): Int = if (log.isFile) log.readLines().size else 0
  }

  private companion object {
    val RATES = listOf(24, 30, 60)
    const val SOURCE_RATE = 30
    val CLIP_LENGTH = 3.seconds
    const val SCALE = 0.56f
    val FRAME = Size(640, 360)
    const val HUGE_SIDE = 30_000
    const val MOST_FRAMES = 50
    const val PICTURE_TOLERANCE = 8
    const val DARK = 64
    const val LIGHT = 192
    const val PQ_DARK = 150
    const val PQ_LIGHT = 480
    const val NANOS_PER_MILLI = 1_000_000L
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
  }
}
