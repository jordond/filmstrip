package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.ATTRIBUTES
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.CAPABILITIES
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.FRAME
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.FRAME_RECT
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.SPAN
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.at
import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.AnimatedPicture
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPeakRefusal
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedPictures.COUNTER_CANVAS
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import dev.jordond.filmstrip.test.AnimatedWebps
import dev.jordond.filmstrip.test.CounterCells
import dev.jordond.filmstrip.test.LossyWebpStills
import dev.jordond.filmstrip.test.OrientedAnimations
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetBitmapInfo
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapAlphaInfoMask
import platform.CoreGraphics.kCGBitmapByteOrderMask
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Animated picture overlays resolved for Core Image, read back off the frame the overlay lands on.
 *
 * Every expected frame index is the file's own timeline put on the output slot of the time the step is handed, and
 * every expected canvas is the fixture painter's. The reads cover the middle of every frame of the first play and of
 * the second.
 */
@OptIn(ExperimentalForeignApi::class)
class AnimatedPictureHardwareTest {
  private val context = renderingContext()

  @Test
  fun `every split WebP fixture still reads exactly through the data provider copy`() {
    LossyWebpStills.ALL.forEach { still ->
      val bytes = AnimatedWebps.still(still.frameChunks(), still.width, still.height)
      val held = bytes.nsData().withImageSource { (it.bitmapCodes() as PictureRead.Read).value }!!
      val image = checkNotNull(held.bitmap)
      val info = CGImageGetBitmapInfo(image)
      val opaque = (info and kCGBitmapAlphaInfoMask) == CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value
      withClue("${still.name} is stored straight, in the byte order the copy is read in") {
        (opaque || (info and kCGBitmapAlphaInfoMask) == CGImageAlphaInfo.kCGImageAlphaLast.value) shouldBe true
        (info and kCGBitmapByteOrderMask) shouldBe 0u
      }
      val data = checkNotNull(CGDataProviderCopyData(CGImageGetDataProvider(image)))
      val row = CGImageGetBytesPerRow(image).toInt()
      val read = checkNotNull(CFDataGetBytePtr(data))
      check(CFDataGetLength(data) >= row.toLong() * still.height)
      val truth = still.truth()
      for (y in 0 until still.height) {
        for (x in 0 until still.width * CHANNELS) {
          if (opaque && x % CHANNELS == RGB) continue
          val expected = truth[y * still.width * CHANNELS + x].toInt() and BYTE
          withClue("${still.name} at $x,$y") { (read[y * row + x].toInt() and BYTE) shouldBe expected }
        }
      }
      CFRelease(data)
    }
  }

  @Test
  fun `every split WebP fixture still reads exactly through stored bands`() {
    LossyWebpStills.ALL.forEach { still ->
      val bytes = AnimatedWebps.still(still.frameChunks(), still.width, still.height)

      val samples = checkNotNull(stillSamplesOf(bytes)) { "${still.name} did not decode" }

      samples.width shouldBe still.width
      samples.height shouldBe still.height
      val truth = still.truth()
      samples.rgba.indices.forEach { index ->
        val sample = samples.rgba[index].toInt() and SAMPLE_MASK
        withClue("${still.name} sample $index") { (sample / EIGHT_BIT_STEP) shouldBe (truth[index].toInt() and BYTE) }
        (sample % EIGHT_BIT_STEP) shouldBe 0
      }
    }
  }

  @Test
  fun `a GIF an APNG and a WebP draw the frame their timeline names across three plays`() {
    val counters = AnimatedPictures.counterOps()
    mapOf(
      "GIF" to AnimatedGifs.bytes(COUNTER_CANVAS, counters, loops = 0),
      "APNG" to AnimatedApngs.bytes(COUNTER_CANVAS, counters),
      "WebP" to AnimatedWebps.bytes(COUNTER_CANVAS, counters),
    ).forEach { (name, bytes) ->
      val timeline = pictureOf(bytes).timeline
      val step = stepFor(overlayOf(bytes, COUNTER_CANVAS), ATTRIBUTES)

      val shown =
        (0 until COUNTER_SLOTS step COUNTER_STEP).map { slot ->
          val time = outputSlotTime(slot.toLong(), RATE)
          val read = counterIndexOf(step.apply(background(), at(time)))
          withClue("$name slot $slot") { read shouldBe timeline.frameAt(SPAN.start, time, RATE) }
          read
        }

      withClue("$name reads every frame and then plays again") {
        shown.distinct().size shouldBe AnimatedPictures.COUNTER_FRAMES
        (shown.indices.drop(1).any { shown[it] < shown[it - 1] }) shouldBe true
      }
    }
  }

  @Test
  fun `a tagged picture lands every frame turned the way its tag says`() {
    for (exif in 1..8) {
      val orientation = PictureOrientation.ofExif(exif)
      assertEveryFrame("APNG exif $exif", OrientedAnimations.apng(exif), OrientedAnimations.OPS, orientation)
    }
  }

  @Test
  fun `two overlays of one picture hold one set of frames for the lowering`() {
    val bytes = AnimatedApngs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED)
    val image = ImageSource.ofBytes(bytes)
    val first = ImageOverlay(image, Corner.TopStart)
    val second = ImageOverlay(image, Corner.BottomEnd)
    val animations = SharedAnimations()
    animations.readFor(Composition(tracks = listOf(Track(emptyList())), effects = listOf(first, second)))

    val a = first.sharedAnimation(animations, bytes, ATTRIBUTES)
    val b = second.sharedAnimation(animations, bytes, ATTRIBUTES)

    b shouldBeSameInstanceAs a
    val cells = a.cells.shouldBeInstanceOf<PictureRead.Read<SheetPixels>>()
    b.cells.shouldBeInstanceOf<PictureRead.Read<SheetPixels>>().value shouldBeSameInstanceAs cells.value
  }

  @Test
  fun `hostile frames land the painter's canvas in the middle of two plays`() {
    val shared = AnimatedPictures.SHARED
    val previous = AnimatedPictures.WITH_PREVIOUS
    val source = AnimatedPictures.WITH_SOURCE
    val canvas = AnimatedPictures.CANVAS
    mapOf(
      "GIF shared" to (AnimatedGifs.bytes(canvas, shared, loops = 0) to shared),
      "GIF previous" to (AnimatedGifs.bytes(canvas, previous, loops = 0) to previous),
      "APNG shared" to (AnimatedApngs.bytes(canvas, shared) to shared),
      "APNG previous" to (AnimatedApngs.bytes(canvas, previous) to previous),
      "APNG source" to (AnimatedApngs.bytes(canvas, source) to source),
      "APNG indexed" to (AnimatedApngs.bytes(canvas, shared, indexed = true) to shared),
      "APNG default is a frame" to
        (
          AnimatedApngs.bytes(canvas, AnimatedPictures.DEFAULT_IS_FRAME, defaultIsFrame = true) to
            AnimatedPictures.DEFAULT_IS_FRAME
        ),
      "WebP shared" to (AnimatedWebps.bytes(canvas, shared) to shared),
      "WebP source" to (AnimatedWebps.bytes(canvas, source) to source),
      "WebP lossy" to (AnimatedWebps.bytes(canvas, AnimatedPictures.LOSSY_FRAMES) to AnimatedPictures.LOSSY_FRAMES),
    ).forEach { (name, file) ->
      val (bytes, ops) = file
      assertEveryFrame(name, bytes, ops)
    }
  }

  @Test
  fun `a sixteen bit APNG lands the painter's canvas`() {
    val shared = AnimatedPictures.SHARED

    assertEveryFrame("sixteen bit", AnimatedApngs.bytes(AnimatedPictures.CANVAS, shared, sixteenBit = true), shared)
  }

  @Test
  fun `a tagged flipbook played forward holds two float cells and converts each cell once`() {
    val frames = taggedCounters()
    val cells = frames.cells()
    val timeline = frames.picture.timeline

    cells.capacity shouldBe 2
    val slots = (timeline.cycle.inWholeMilliseconds * RATE / MILLIS).toInt()
    var drawn = -1
    for (slot in 0 until slots) {
      val time = outputSlotTime(slot.toLong(), RATE)
      val crop = frames.flipbook.cellAt(SPAN.start, time, RATE)
      counterIndexOfCell(cells, crop, null) shouldBe timeline.frameAt(SPAN.start, time, RATE)
      cells.kept shouldBe minOf(2, cells.conversions)
      drawn = timeline.frameAt(SPAN.start, time, RATE)
    }

    drawn shouldBe AnimatedPictures.COUNTER_FRAMES - 1
    cells.kept shouldBe 2
    cells.conversions shouldBe AnimatedPictures.COUNTER_FRAMES
  }

  @Test
  fun `a backward seek on the tagged route lands the right cell and converts the cell it lands on`() {
    val frames = taggedCounters()
    val cells = frames.cells()
    val last = AnimatedPictures.COUNTER_FRAMES - 1
    (0..last).forEach { counterIndexOfCell(cells, frames.grid.cells[it], null) }

    (last downTo 0).forEach { index ->
      withClue("frame $index") { counterIndexOfCell(cells, frames.grid.cells[index], null) shouldBe index }
    }

    withClue("the last two stayed kept and every cell before them was made again") {
      cells.conversions shouldBe AnimatedPictures.COUNTER_FRAMES + (last - 1)
    }
  }

  @Test
  fun `a backward seek on the HDR route lands the right cell`() {
    val frames = drawnOf(AnimatedApngs.bytes(COUNTER_CANVAS, AnimatedPictures.counterOps()))
    val cells = frames.cells()
    val last = AnimatedPictures.COUNTER_FRAMES - 1

    (0..last).forEach { counterIndexOfCell(cells, frames.grid.cells[it], HdrTransfer.Pq) }
    (last downTo 0 step 7).forEach { index ->
      counterIndexOfCell(cells, frames.grid.cells[index], HdrTransfer.Pq) shouldBe index
    }
  }

  @Test
  fun `a float cell converts in the time this host takes on a backward seek`() {
    val tagged = pictureColorOf(taggedCounters())
    listOf(Size(CELL_WIDE, CELL_SHORT), Size(CELL_WIDE, CELL_WIDE)).forEach { size ->
      val codes = noisyCodes(size).let { OverlayPixels(it, { tagged }) }
      val sdr = median(codes) { it.enteringFrame(null, ColorSpace.Bt709) }
      val pq = median(codes) { it.enteringFrame(HdrTransfer.Pq, ColorSpace.Bt2020) }
      println("float cell ${size.width}x${size.height}: tagged SDR $sdr ms, PQ $pq ms")
    }
  }

  @Test
  fun `an animated AVIF is refused by name`() {
    val spec = overlayOf(avis(), COUNTER_CANVAS)

    val refused = resolve(spec, ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldBe ANIMATED_AVIF_REFUSED
  }

  @Test
  fun `a picture past the ceiling is refused with the count of the Apple copies`() {
    val bytes = AnimatedApngs.bytes(COUNTER_CANVAS, AnimatedPictures.counterOps())
    val picture = pictureOf(bytes)
    val refusal =
      checkNotNull(animatedPeakRefusal(picture, AnimatedCopies.Apple, BELOW_APPLE_COPIES, bytes.size.toLong()))

    val read = animatedRead(bytes, AnimatedCopies.Apple, ceiling = BELOW_APPLE_COPIES)

    read.shouldBeInstanceOf<AnimatedFlipbookRead.Refused>().message shouldBe refusal
  }

  @Test
  fun `a flipbook with no output frame rate is refused by name`() {
    val bytes = AnimatedApngs.bytes(COUNTER_CANVAS, AnimatedPictures.counterOps())
    val unset = Attributes(FRAME, FRAME, FRAME, ColorSpace.Bt709, null, null, SPAN)

    resolve(overlayOf(bytes, COUNTER_CANVAS), unset).shouldBeInstanceOf<EffectResolution.Unsupported>().message shouldBe
      ANIMATED_NO_GRID
  }

  // Every frame of a file, read in the middle of its first play and of its second.
  private fun assertEveryFrame(
    name: String,
    bytes: ByteArray,
    ops: List<Op>,
    orientation: PictureOrientation = PictureOrientation.Normal,
  ) {
    val stored = AnimatedPictures.CANVAS
    val canvas = orientation.orientedSize(stored)
    val painted = AnimatedPictures.canvases(stored, ops).map { orientedSamples(orientation, it, stored) }
    val timeline = pictureOf(bytes).timeline
    val step = stepFor(overlayOf(bytes, canvas), ATTRIBUTES)
    val frame = ops.first().delayMillis.milliseconds
    val times = (0 until ops.size * 2).map { frame * it + frame / 2 }

    val shown =
      times.map { time ->
        val index = timeline.frameAt(SPAN.start, time, RATE)
        val read = texelsOf(step.apply(background(), at(time)), canvas)
        painted[index].forEachIndexed { texel, argb ->
          withClue("$name frame $index at $time texel $texel") { read.assertNear(texel, argb) }
        }
        index
      }

    withClue("$name reads every frame") { shown.distinct().size shouldBe ops.size }
  }

  // The painter's texel over black, the colour a drawn texel of that alpha lands on an opaque black frame.
  private fun FloatArray.assertNear(
    texel: Int,
    argb: Int,
  ) {
    val alpha = (argb ushr ALPHA_SHIFT) / MAX_CODE
    val expected = listOf(argb shr RED_SHIFT, argb shr GREEN_SHIFT, argb).map { (it and BYTE) * alpha }
    for (channel in 0 until RGB) {
      val read = this[texel * CHANNELS + channel] * MAX_CODE
      withClue("channel $channel read $read, expected ${expected[channel]}") {
        (abs(read - expected[channel]) <= TOLERANCE) shouldBe true
      }
    }
  }

  private fun counterIndexOf(frame: CIImage): Int {
    val cell = AnimatedPictures.COUNTER_CELL
    val read = readCorner(frame, cell, null)
    return CounterCells.indexOf(packed(read, cell))
  }

  private fun counterIndexOfCell(
    cells: SheetPixels,
    crop: PictureCrop,
    transfer: HdrTransfer?,
  ): Int {
    val colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020
    val image = cells.enteringFrame(crop, transfer, colorSpace)
    val placement = OverlayPlacement(cells.cell, Anchor(0f, 0f), Anchor(0f, 0f))
    val frame = image.compositedOnto(background(), placement, FRAME, 1f, transfer, colorSpace)
    val cell = AnimatedPictures.COUNTER_CELL
    return CounterCells.indexOf(packed(readCorner(frame, cell, transfer), cell))
  }

  // The texels of the frame's first rectangle of [size], as packed ARGB so the fixture's reader can say what they hold.
  private fun packed(
    read: FloatArray,
    size: Size,
  ): IntArray =
    IntArray(size.width * size.height) { texel ->
      val white = (0 until RGB).sumOf { read[texel * CHANNELS + it].toDouble() } / RGB > HALF
      if (white) WHITE else BLACK
    }

  private fun texelsOf(
    frame: CIImage,
    size: Size,
  ): FloatArray = readCorner(frame, size, null)

  private fun readCorner(
    frame: CIImage,
    size: Size,
    transfer: HdrTransfer?,
  ): FloatArray {
    val space =
      if (transfer ==
        null
      ) {
        ColorSpace.Bt709.createCodeSpace()
      } else {
        CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_PQ)
      }
    val floats = FloatArray(size.width * size.height * CHANNELS)
    floats.usePinned { pinned ->
      context.render(
        image = frame,
        toBitmap = pinned.addressOf(0),
        rowBytes = (size.width * CHANNELS * Float.SIZE_BYTES).toLong(),
        bounds =
          CGRectMake(
            0.0,
            (FRAME.height - size.height).toDouble(),
            size.width.toDouble(),
            size.height.toDouble(),
          ),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    }
    CGColorSpaceRelease(space)
    return floats
  }

  private fun background(): CIImage = CIImage(color = CIColor.blackColor).imageByCroppingToRect(FRAME_RECT)

  private fun overlayOf(
    bytes: ByteArray,
    canvas: Size,
  ) = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart, 0f, canvas.width.toFloat() / FRAME.width)

  private fun resolve(
    spec: ImageOverlay,
    attributes: Attributes,
  ): EffectResolution = BuiltInEffectResolver().resolve(spec, CAPABILITIES, attributes)!!

  private fun stepFor(
    spec: ImageOverlay,
    attributes: Attributes,
  ): CoreImageEffect = resolve(spec, attributes).shouldBeInstanceOf<EffectResolution.Resolved>().effect.step

  private fun pictureOf(bytes: ByteArray): AnimatedPicture =
    animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>().picture

  private fun drawnOf(bytes: ByteArray): AnimatedFlipbookRead.Drawn =
    animatedRead(bytes, AnimatedCopies.Apple).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()

  private fun AnimatedFlipbookRead.Drawn.cells(): SheetPixels =
    framesAsPixels().shouldBeInstanceOf<PictureRead.Read<SheetPixels>>().value

  private fun taggedCounters(): AnimatedFlipbookRead.Drawn =
    drawnOf(withDisplayP3(AnimatedApngs.bytes(COUNTER_CANVAS, AnimatedPictures.counterOps())))
      .also { checkNotNull(it.color) { "the file is tagged" } }

  private fun pictureColorOf(drawn: AnimatedFlipbookRead.Drawn) = checkNotNull(drawn.color)

  private fun noisyCodes(size: Size): HeldCodes {
    val samples =
      ShortArray(size.width * size.height * CHANNELS) { ((it * NOISE_STEP) % CODES * EIGHT_BIT_STEP).toShort() }
    for (alpha in CHANNELS - 1 until samples.size step CHANNELS) samples[alpha] = TOP_SAMPLE.toShort()
    val frame =
      dev.jordond.filmstrip.picture.animated
        .FrameSamples(size.width, size.height, samples, false)
    return (frame.frameCodes() as PictureRead.Read).value
  }

  private companion object {
    const val RATE = 30
    const val CHANNELS = 4
    const val RGB = 3
    const val BYTE = 0xFF
    const val SAMPLE_MASK = 0xFFFF
    const val EIGHT_BIT_STEP = 257
    const val TOP_SAMPLE = 0xFFFF
    const val HALF = 0.5
    const val MAX_CODE = 255f
    const val TOLERANCE = 2f
    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val COUNTER_SLOTS = 240
    const val COUNTER_STEP = 1
    const val MILLIS = 1000L
    const val CELL_WIDE = 480
    const val CELL_SHORT = 270
    const val CODES = 251
    const val NOISE_STEP = 7

    // Above what the reader holds while it walks the file and below the 60 frames of codes and floats Apple keeps.
    const val BELOW_APPLE_COPIES = 1_000_000L
  }
}
