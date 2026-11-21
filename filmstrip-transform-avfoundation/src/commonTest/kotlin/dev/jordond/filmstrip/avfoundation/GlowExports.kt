package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
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
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/**
 * The flat clips, exports and reads a test of a glowing filter shares, over a real Apple export with the filters
 * installed.
 *
 * Every file is written to the temporary directory under [tag], so two classes never write over each other.
 */
internal class GlowExports(
  private val tag: String,
) {
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

  /**
   * Holds [edit] over a flat [source] to [glowOver], which works the signal over a picture with no filter.
   *
   * The render is held to the signal over the same render with no filter. The file is held against the export with
   * no filter, in the codes it holds, and through luma, which an encoder moves the least.
   */
  suspend fun assertGlowsOnTheSignal(
    source: MediaSource,
    edit: Composition,
    glowOver: (Rgb) -> Rgb,
  ) {
    val plainEdit = Composition(listOf(Track(listOf(Clip(source)))))
    val rendered = withContext(Dispatchers.Default) { renderedFrameOf(graphOf(edit), 0) }.at(CENTRE, CENTRE)
    val renderedPlain = withContext(Dispatchers.Default) { renderedFrameOf(graphOf(plainEdit), 0) }.at(CENTRE, CENTRE)
    assertCodes(rendered, glowOver(renderedPlain), "the rendered glow over $renderedPlain")

    val glowed = export(edit, "glow-filter")
    val plain = export(plainEdit, "glow-plain")
    val read = codesOf(glowed).average(CENTRE, CENTRE)
    val under = codesOf(plain).average(CENTRE, CENTRE)
    val code = MID_RESPONSE / MAX_CODE
    val lit = glowOver(Rgb(code, code, code))
    val wanted = doubleArrayOf(lit.r - code, lit.g - code, lit.b - code).map { it * MAX_CODE }
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
    remove(glowed)
    remove(plain)
  }

  suspend fun graphOf(edit: Composition): AvComposition {
    val resolved = avFoundationExportEngine(components).resolve(edit, ExportSpec())
    return withContext(Dispatchers.Default) {
      resolved.shouldBeInstanceOf<ResolveResult.Resolved>().composition.toAvComposition()
    }
  }

  fun lumaOf(rgb: Triple<Int, Int, Int>): Double = KR * rgb.first + (1.0 - KR - KB) * rgb.second + KB * rgb.third

  // Where the bright picture stops along the frame's middle row, read off the plain export.
  fun pictureRight(path: String): Float {
    val frame = frameOf(path)
    val half = frame.average(CENTRE, CENTRE).first.toDouble() / 2
    var fx = CENTRE
    while (fx < 1f - BAR_OFFSET && frame.average(fx, CENTRE).first.toDouble() > half) fx += SCAN_STEP
    return fx
  }

  suspend fun redOfSwatch(
    edit: Composition,
    name: String,
  ): Double {
    val path = export(edit, name)
    val read = frameOf(path).average(SWATCH_FX, SWATCH_FY).first.toDouble()
    remove(path)
    return read
  }

  fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(solidPng(SWATCH_PX, SWATCH_PX, Triple(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE))),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  suspend fun flatClip(
    name: String,
    code: Int,
  ): MediaSource {
    val path = NSTemporaryDirectory() + "filmstrip-$tag-flat-$name.mp4"
    remove(path)
    val primaries = AVVideoColorPrimaries_ITU_R_709_2.orEmpty()
    val matrix = AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()
    withContext(
      Dispatchers.Default,
    ) { writeTaggedBars(path, FRAMES, primaries, matrix, listOf(Triple(code, code, code))) }
    return MediaSource.of(path)
  }

  suspend fun export(
    edit: Composition,
    name: String,
  ): String {
    val path = NSTemporaryDirectory() + "filmstrip-$tag-$name.mp4"
    remove(path)
    val status =
      withContext(Dispatchers.Default) { filmstrip.export(edit, ExportSpec(), MediaSink.of(path)).toList().last() }
    status.shouldBeInstanceOf<ExportStatus.Success>()
    return path
  }

  @OptIn(ExperimentalForeignApi::class)
  fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
  }

  companion object {
    val TIMEOUT = 3.minutes
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val FRAMES = 15
    const val CENTRE = 0.5f
    const val MAX_CODE = 255.0
    const val BLACK = 0xFF000000.toInt()
    const val BAR_FILL = 0xFF2F4F6F.toInt()

    // Past the picture's edge by enough of the glow's sigma that a patch's read holds no picture.
    const val BAR_OFFSET = 0.012f
    const val SCAN_STEP = 0.005f

    // On the film's response ramp and half way up the bloom's default knee, so a glow neither vanishes nor clamps.
    const val MID_RESPONSE = 204
    const val BRIGHT = 242
    const val DARK = 51

    // The swatch glows like the mid picture, and the clip under it gives no light of its own.
    const val SWATCH_CODE = 204
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    const val KR = 0.2126
    const val KB = 0.0722
  }
}
