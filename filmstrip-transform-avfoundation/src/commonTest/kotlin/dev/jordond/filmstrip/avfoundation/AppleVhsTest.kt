package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Where a [Vhs] lands in a real export: what it reaches at each scope, and what stays clean.
 *
 * How close each pixel lands to the signal is read off the kernel's own output in the filters module. The figures here
 * are what survives the encoder. Reads take the mean of the channels, since chroma noise moves any one channel by more
 * than the lift a read looks for.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleVhsTest {
  private val filmstrip =
    Filmstrip {
      avFoundationBackend()
      filters()
    }

  @Test
  fun `a tone mapped export lands on the signal from its first frame`() =
    runTest(timeout = TIMEOUT) {
      HdrTransfer.entries.forEach { transfer ->
        val vhs = Vhs(intensity = 0.5f)
        val clip = Clip(MediaSource.of(hdrFixture(transfer)))
        val plainPath = exported(EditComposition(listOf(Track(listOf(clip)))), HDR_SPEC, "plain-$transfer")
        val vhsPath = exported(EditComposition(listOf(Track(listOf(clip))), listOf(vhs)), HDR_SPEC, "signal-$transfer")
        val plain = codeFramesOf(plainPath, 1).single().at(CENTRE, CENTRE)
        val frames = codeFramesOf(vhsPath, LATER_FRAME + 1)
        val means =
          listOf(0, 1, LATER_FRAME).associateWith { index ->
            val read = frames[index].at(CENTRE, CENTRE).mean()
            val wanted = signalOverPatch(vhs, index, frames[index].width, frames[index].height, plain).mean()
            withClue("$transfer frame $index read $read against the signal's $wanted") {
              abs(read - wanted) * MAX_CODE shouldBeLessThan TONE_MAP_TOLERANCE_CODES
            }
            read
          }
        withClue("$transfer frame 0 read ${means[0]} and frame 1 read ${means[1]}") {
          abs(means.getValue(0) - means.getValue(1)) * MAX_CODE shouldBeLessThan TONE_MAP_TOLERANCE_CODES
        }
        remove(plainPath)
        remove(vhsPath)
      }
    }

  @Test
  fun `an hdr source is tone mapped and reported under a vhs`() =
    runTest(timeout = TIMEOUT) {
      val edit = EditComposition(listOf(Track(listOf(Clip(MediaSource.of(hdrFixture(HdrTransfer.Pq)))))), listOf(Vhs()))

      val verdict = filmstrip.plan(edit, ExportSpec(heightPx = HDR_HEIGHT)).shouldBeInstanceOf<Verdict.Degraded>()

      verdict.adjustments.map { it.kind } shouldContain AdjustmentKind.HdrToneMapped
    }

  @Test
  fun `keep hdr is refused by name`() =
    runTest(timeout = TIMEOUT) {
      val edit = EditComposition(listOf(Track(listOf(Clip(MediaSource.of(hdrFixture(HdrTransfer.Pq)))))), listOf(Vhs()))

      val refused =
        filmstrip
          .plan(edit, ExportSpec(hdr = HdrMode.KeepHdr))
          .shouldBeInstanceOf<Verdict.Incapable>()

      refused.reasons.joinToString { it.message } shouldContain Vhs.ID
    }

  @Test
  fun `no filters on the builder refuses by name`() =
    runTest(timeout = TIMEOUT) {
      val edit = EditComposition(listOf(Track(listOf(Clip(MediaSource.of(fixture(CLIP)))))), listOf(Vhs()))

      val refused =
        Filmstrip { avFoundationBackend() }
          .plan(edit, ExportSpec())
          .shouldBeInstanceOf<Verdict.Incapable>()

      refused.reasons.joinToString { it.message } shouldContain Vhs.ID
    }

  @Test
  fun `a composition vhs reaches the bars`() =
    runTest(timeout = TIMEOUT) {
      val vhs = Vhs()
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val taped = meanOfBar(barsEdit(Clip(MediaSource.of(fixture(CLIP))), listOf(crop, vhs)), "composition-bars")
      val plain = meanOfBar(barsEdit(Clip(MediaSource.of(fixture(CLIP))), listOf(crop)), "plain-bars")

      taped - plain shouldBeGreaterThan halfLift(vhs)
    }

  @Test
  fun `a clip vhs leaves the composition bars alone`() =
    runTest(timeout = TIMEOUT) {
      val vhs = Vhs()
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val taped =
        meanOfBar(barsEdit(Clip(MediaSource.of(fixture(CLIP)), effects = listOf(vhs)), listOf(crop)), "clip-bars")
      val plain = meanOfBar(barsEdit(Clip(MediaSource.of(fixture(CLIP))), listOf(crop)), "plain-bars")

      abs(taped - plain) shouldBeLessThan halfLift(vhs)
    }

  @Test
  fun `a clip vhs moves the picture into a canvas bar and leaves the fill alone`() =
    runTest(timeout = TIMEOUT) {
      assertCanvasRead(Fill.Solid(BAR_FILL), Fill.Solid(BAR_FILL), "canvas-solid")
    }

  // A blurred fill turns a canvas's bars black, so the same crop over a black fill is what they should read.
  @Test
  fun `a clip vhs leaves a blurred fill's canvas bars black and moves the picture over them`() =
    runTest(timeout = TIMEOUT) {
      assertCanvasRead(Fill.Blurred(), Fill.Solid(BLACK), "canvas-blurred")
    }

  @Test
  fun `an overlay at the same scope stays clean`() =
    runTest(timeout = TIMEOUT) {
      val vhs = Vhs()
      val edit =
        EditComposition(listOf(Track(listOf(Clip(MediaSource.of(fixture(CLIP)))))), listOf(swatch(), vhs))

      abs(meanOfSwatch(edit, "overlay-clean") - cleanSwatch()) shouldBeLessThan halfLift(vhs)
    }

  @Test
  fun `a clip overlay under a composition vhs is burned`() =
    runTest(timeout = TIMEOUT) {
      val vhs = Vhs()
      val edit =
        EditComposition(
          listOf(Track(listOf(Clip(MediaSource.of(fixture(CLIP)), effects = listOf(swatch()))))),
          listOf(vhs),
        )

      abs(meanOfSwatch(edit, "overlay-burned") - cleanSwatch()) shouldBeGreaterThan halfLift(vhs)
    }

  private fun barsEdit(
    clip: Clip,
    effects: List<EffectSpec>,
  ) = EditComposition(listOf(Track(listOf(clip))), effects, fill = Fill.Solid(BLACK))

  // Reads the canvas [fill] leaves under a clip Vhs against the same crop over [plainFill] with no Vhs.
  private suspend fun assertCanvasRead(
    fill: Fill,
    plainFill: Fill,
    name: String,
  ) {
    val vhs = Vhs(intensity = 0.5f)
    val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
    val edit = { effects: List<EffectSpec>, over: Fill ->
      EditComposition(listOf(Track(listOf(Clip(MediaSource.of(fixture(CLIP)), effects = effects)))), fill = over)
    }
    val spec = ExportSpec()
    val tapedPath = exported(edit(listOf(crop, vhs), fill), spec, name)
    val plainPath = exported(edit(listOf(crop), plainFill), spec, "$name-plain")
    val taped = frameOf(tapedPath)
    val plain = frameOf(plainPath)
    remove(tapedPath)
    remove(plainPath)
    val content = placementOf(SOURCE, listOf(listOf(crop)), Size(taped.width, taped.height)).drawnRect
    val height = taped.height
    // A read averages a small patch at the foot of the frame, and the head band's shift grows down it, so a column is
    // picked to hold for every row in it.
    val y = 1f - (FrameProbe.PATCH / 2).toFloat() / height
    val shiftAt = { fromBottom: Int ->
      val row = VhsSignal.virtualRow(height - fromBottom, height)
      VhsSignal.rowShift(vhs.seed, VhsSignal.tickAt(0.0), row, VhsParams.of(vhs)).toFloat()
    }
    val least = shiftAt(FrameProbe.PATCH)
    val most = shiftAt(1)
    withClue("the head band has to reach into the bar for this to tell") { (least > MIN_SHIFT) shouldBe true }

    val movedIn = taped.average(content.right + least / 2, y)
    val movedPlain = plain.average(content.right + least / 2, y)
    withClue("picture moved into the right bar read $movedIn against $movedPlain") {
      movedIn.widestGapTo(movedPlain) shouldBeGreaterThan PICTURE_APART
    }
    val clear =
      mapOf(
        "past the moved picture" to Pair(content.right + most + MIN_SHIFT, y),
        "the start of the picture's own rect" to Pair(content.left + least / 2, y),
        "a bar on a row with no head band" to Pair(content.right + least / 2, 0.5f),
      )
    clear.forEach { (what, at) ->
      val read = taped.average(at.first, at.second)
      // The same bar's read with no Vhs, on the same row, since the plain frame still holds its picture at the rect.
      val wanted = plain.average(content.right + least / 2, at.second)
      withClue("$what read $read against $wanted") {
        abs(read.mean() - wanted.mean()) shouldBeLessThan halfLift(vhs)
      }
    }
  }

  // The signal over a read's patch, on a flat picture, a grid of pixels at a time.
  private fun signalOverPatch(
    vhs: Vhs,
    index: Int,
    width: Int,
    height: Int,
    picture: Rgb,
  ): Rgb {
    val tick = VhsSignal.tickAt(index / FRAME_RATE)
    val source = { _: Double -> doubleArrayOf(picture.r, picture.g, picture.b) }
    val pixels =
      (-PATCH_HALF until PATCH_HALF step PATCH_STEP).flatMap { dy ->
        (-PATCH_HALF until PATCH_HALF step PATCH_STEP).map { dx ->
          val row = VhsSignal.virtualRow((height * CENTRE).toInt() + dy, height)
          val x = ((width * CENTRE).toInt() + dx + 0.5) / width
          VhsSignal.pixelAt(x, row, tick, vhs.seed, width, VhsParams.of(vhs), source)
        }
      }
    return Rgb(pixels.map { it[0] }.average(), pixels.map { it[1] }.average(), pixels.map { it[2] }.average())
  }

  private suspend fun meanOfBar(
    edit: EditComposition,
    name: String,
  ): Double {
    val path = exported(edit, ExportSpec(), name)
    val read = frameOf(path).average(0.5f, BAR_MIDDLE).mean()
    remove(path)
    return read
  }

  private suspend fun meanOfSwatch(
    edit: EditComposition,
    name: String,
  ): Double {
    val path = exported(edit, ExportSpec(), name)
    val read = frameOf(path).average(SWATCH_FX, SWATCH_FY).mean()
    remove(path)
    return read
  }

  private suspend fun cleanSwatch(): Double =
    meanOfSwatch(
      EditComposition(listOf(Track(listOf(Clip(MediaSource.of(fixture(CLIP)))))), listOf(swatch())),
      "overlay",
    )

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(solidPng(SWATCH_PX, SWATCH_PX, Triple(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE))),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private suspend fun exported(
    edit: EditComposition,
    spec: ExportSpec,
    name: String,
  ): String {
    val path = NSTemporaryDirectory() + "filmstrip-vhs-$name.mp4"
    val status = withContext(Dispatchers.Default) { filmstrip.export(edit, spec, MediaSink.of(path)).toList().last() }
    status
      .shouldBeInstanceOf<ExportStatus.Success>()
      .info.video
      .shouldNotBeNull()
      .hdrTransfer
      .shouldBeNull()
    return path
  }

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private fun Rgb.mean(): Double = (r + g + b) / 3.0

  private fun Triple<Int, Int, Int>.mean(): Double = (first + second + third) / 3.0

  private fun Triple<Int, Int, Int>.widestGapTo(other: Triple<Int, Int, Int>): Int =
    maxOf(abs(first - other.first), abs(second - other.second), abs(third - other.third))

  // A read tells lifted from clean when it lands closer to its own side of this.
  private fun halfLift(vhs: Vhs): Double = VhsParams.of(vhs).lift * MAX_CODE / 2

  private companion object {
    val TIMEOUT = 3.minutes
    const val CLIP = "apple_export_a.mp4"
    val SOURCE = Size(640, 360)
    const val HDR_HEIGHT = 720
    val HDR_SPEC = ExportSpec(heightPx = HDR_HEIGHT, hdr = HdrMode.ToneMapToSdr)
    const val FRAME_RATE = 30.0
    const val CENTRE = 0.5f
    const val LATER_FRAME = 4
    const val MAX_CODE = 255.0
    const val PATCH_HALF = 8
    const val PATCH_STEP = 4
    const val BLACK = 0xFF000000.toInt()
    const val BAR_MIDDLE = 0.1f
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val BAR_FILL = 0xFF2F4F6F.toInt()

    // Picture moved over a flat bar, which is far past what the encoder does to the bar on its own.
    const val PICTURE_APART = 16
    const val MIN_SHIFT = 0.01f

    const val SWATCH_CODE = 0x20
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f
  }
}
