package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Where a [Film] lands in a real Apple export: what it reaches at each scope, and what stays clean.
 *
 * Every read here is of the glow, whose figure is large enough to survive an encoder, and is held against the signal
 * worked over the same read with no [Film]. How close each pixel lands to the signal, grain included, is read off the
 * kernels' own output in the filters module.
 */
class AppleFilmHardwareTest {
  private val components =
    ComponentRegistry
      .Builder()
      .addEffectResolver(BuiltInEffectResolver())
      .addEffectResolver(FilterEffectResolver())
      .build()
  private val filmstrip =
    Filmstrip {
      avFoundationBackend()
      filters()
    }

  @Test
  fun `a composition film glows the picture on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip("mid", MID_RESPONSE)

      assertGlowsOnTheSignal(source, EditComposition(listOf(Track(listOf(Clip(source)))), listOf(GLOW_ONLY)))
    }

  @Test
  fun `a clip film glows the picture on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip("mid", MID_RESPONSE)

      assertGlowsOnTheSignal(source, EditComposition(listOf(Track(listOf(Clip(source, effects = listOf(GLOW_ONLY)))))))
    }

  @Test
  fun `a composition film glows from the picture into a bar`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatClip("bright", BRIGHT)
      val edit = { effects: List<EffectSpec> ->
        EditComposition(listOf(Track(listOf(Clip(source)))), effects, fill = Fill.Solid(BLACK))
      }

      val plain = export(edit(listOf(crop)), "bar-plain")
      val filmed = export(edit(listOf(crop, GLOW_ONLY)), "bar-film")

      val fx = pictureRight(plain) + BAR_OFFSET
      val lift =
        frameOf(filmed).average(fx, CENTRE).first.toDouble() - frameOf(plain).average(fx, CENTRE).first.toDouble()
      withClue("just off the picture lifted $lift codes") { lift shouldBeGreaterThan WRITTEN_COLOUR_CODES }
      remove(plain)
      remove(filmed)
    }

  @Test
  fun `a clip film leaves a canvas bar as its fill`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatClip("bright", BRIGHT)
      val edit = { effects: List<EffectSpec> ->
        EditComposition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = Fill.Solid(BAR_FILL))
      }

      val plain = export(edit(listOf(crop)), "canvas-plain")
      val filmed = export(edit(listOf(crop, Film(grain = 1f, halation = 1f))), "canvas-film")

      val fx = pictureRight(plain) + BAR_OFFSET
      val read = frameOf(filmed).average(fx, CENTRE).first.toDouble()
      val wanted = frameOf(plain).average(fx, CENTRE).first.toDouble()
      withClue("the bar read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
      remove(plain)
      remove(filmed)
    }

  @Test
  fun `an overlay at the same scope stays clean`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip("dark", DARK)
      val swatch = swatch()

      val plain = redOfSwatch(EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch)), "overlay-plain")
      val filmed =
        redOfSwatch(EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch, GLOW_ONLY)), "overlay-film")

      withClue("the swatch read $filmed against $plain") { abs(filmed - plain) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  @Test
  fun `a clip overlay under a composition film is burned`() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip("dark", DARK), effects = listOf(swatch()))

      val filmed = redOfSwatch(EditComposition(listOf(Track(listOf(clip))), listOf(GLOW_ONLY)), "burned-film")

      val under = SWATCH_CODE / MAX_CODE
      val response = FilmSignal.halationResponse(under, under, under)
      val wanted = FilmSignal.halated(under, under, under, response, FilmParams.of(GLOW_ONLY))[0] * MAX_CODE
      withClue("the glow has to clear what an encoder moves for this to tell") {
        (wanted - SWATCH_CODE) shouldBeGreaterThan WRITTEN_COLOUR_CODES * 2
      }
      withClue("the swatch read $filmed against $wanted") { abs(filmed - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  // The render is held to the signal over the same render with no Film. Each file is held against the export with no
  // Film, in the codes it holds, and through luma, which an encoder moves the least.
  private suspend fun assertGlowsOnTheSignal(
    source: MediaSource,
    edit: EditComposition,
  ) {
    val plainEdit = EditComposition(listOf(Track(listOf(Clip(source)))))
    val rendered = withContext(Dispatchers.Default) { renderedFrameOf(graphOf(edit), 0) }.at(CENTRE, CENTRE)
    val renderedPlain = withContext(Dispatchers.Default) { renderedFrameOf(graphOf(plainEdit), 0) }.at(CENTRE, CENTRE)
    assertCodes(rendered, glowOver(renderedPlain), "the rendered glow over $renderedPlain")

    val filmed = export(edit, "glow-film")
    val plain = export(plainEdit, "glow-plain")
    val read = codesOf(filmed).average(CENTRE, CENTRE)
    val under = codesOf(plain).average(CENTRE, CENTRE)
    val code = MID_RESPONSE / MAX_CODE
    val halated = glowOver(Rgb(code, code, code))
    val wanted = doubleArrayOf(halated.r - code, halated.g - code, halated.b - code).map { it * MAX_CODE }
    val redLift = read.first.toDouble() - under.first.toDouble()
    val lumaLift = lumaOf(read) - lumaOf(under)
    val wantedLuma = wanted[0] * KR + wanted[1] * (1.0 - KR - KB) + wanted[2] * KB

    withClue("the glow has to clear what an encoder moves for this to tell, ${wanted[0]} codes") {
      wanted[0] shouldBeGreaterThan WRITTEN_COLOUR_CODES * 2
    }
    withClue("the picture's luma lifted $lumaLift against $wantedLuma") {
      abs(lumaLift - wantedLuma) shouldBeLessThan WRITTEN_LUMA_CODES
    }
    withClue("the picture's red lifted $redLift against ${wanted[0]}") {
      abs(redLift - wanted[0]) shouldBeLessThan WRITTEN_COLOUR_CODES
    }
    remove(filmed)
    remove(plain)
  }

  private fun glowOver(picture: Rgb): Rgb {
    val response = FilmSignal.halationResponse(picture.r, picture.g, picture.b)
    val halated = FilmSignal.halated(picture.r, picture.g, picture.b, response, FilmParams.of(GLOW_ONLY))
    return Rgb(halated[0], halated[1], halated[2])
  }

  private suspend fun graphOf(edit: EditComposition): AvComposition {
    val resolved = avFoundationExportEngine(components).resolve(edit, ExportSpec())
    return withContext(Dispatchers.Default) {
      resolved.shouldBeInstanceOf<ResolveResult.Resolved>().composition.toAvComposition()
    }
  }

  private fun lumaOf(rgb: Triple<Int, Int, Int>): Double =
    KR * rgb.first + (1.0 - KR - KB) * rgb.second + KB * rgb.third

  // Where the bright picture stops along the frame's middle row, read off the plain export.
  private fun pictureRight(path: String): Float {
    val frame = frameOf(path)
    val half = frame.average(CENTRE, CENTRE).first.toDouble() / 2
    var fx = CENTRE
    while (fx < 1f - BAR_OFFSET && frame.average(fx, CENTRE).first.toDouble() > half) fx += SCAN_STEP
    return fx
  }

  private suspend fun redOfSwatch(
    edit: EditComposition,
    name: String,
  ): Double {
    val path = export(edit, name)
    val read = frameOf(path).average(SWATCH_FX, SWATCH_FY).first.toDouble()
    remove(path)
    return read
  }

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(solidPng(SWATCH_PX, SWATCH_PX, Triple(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE))),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private suspend fun flatClip(
    name: String,
    code: Int,
  ): MediaSource {
    val path = NSTemporaryDirectory() + "filmstrip-film-flat-$name.mp4"
    remove(path)
    val primaries = AVVideoColorPrimaries_ITU_R_709_2.orEmpty()
    val matrix = AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()
    withContext(
      Dispatchers.Default,
    ) { writeTaggedBars(path, FRAMES, primaries, matrix, listOf(Triple(code, code, code))) }
    return MediaSource.of(path)
  }

  private suspend fun export(
    edit: EditComposition,
    name: String,
  ): String {
    val path = NSTemporaryDirectory() + "filmstrip-film-$name.mp4"
    remove(path)
    val status =
      withContext(Dispatchers.Default) { filmstrip.export(edit, ExportSpec(), MediaSink.of(path)).toList().last() }
    status.shouldBeInstanceOf<ExportStatus.Success>()
    return path
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    val GLOW_ONLY = Film(grain = 0f, halation = 1f)
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val FRAMES = 15
    const val CENTRE = 0.5f
    const val MAX_CODE = 255.0
    const val BLACK = 0xFF000000.toInt()
    const val BAR_FILL = 0xFF2F4F6F.toInt()

    // A sigma of the glow past the picture's edge, far enough that a patch's read holds no picture.
    const val BAR_OFFSET = 0.012f
    const val SCAN_STEP = 0.005f

    // Sits on the response's ramp, so the glow neither vanishes nor clamps.
    const val MID_RESPONSE = 204
    const val BRIGHT = 242
    const val DARK = 51

    // The swatch is on the response's ramp too, and the clip under it gives no light of its own.
    const val SWATCH_CODE = 204
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    const val KR = 0.2126
    const val KB = 0.0722
  }
}
