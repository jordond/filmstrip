package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_2020
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPrimaries_P3_D65
import platform.AVFoundation.AVVideoColorPrimaries_SMPTE_C
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_2020
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * SDR clips and stills drawn beside an HDR clip in a tone mapped export.
 *
 * Each is held to the same clip with no HDR in the edit, every bar, rendered before the encoder. Two H.264 encodes of
 * the same frame move a bar by a few codes on their own, more than the routes differ by.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class AppleToneMapSdrClipHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `an sdr clip beside an hdr clip reads what it reads on its own`() =
    runTest(timeout = TIMEOUT) {
      // The fixtures carry no colour tags and what filmstrip writes carries BT.709's, and each reads its own way.
      val untagged = fixture(SDR_FIXTURE)
      val tagged = exported(reencoded(untagged), "sdr-tagged")
      assertReadsAsItDoesAlone(untagged, TONE_MAP_TOLERANCE_CODES)
      // macOS keeps these codes, where iOS runs them through one conversion.
      val floor = if (MACOS) 0.0 else TONE_MAP_TOLERANCE_CODES
      listOf(fixture(HD_SDR_FIXTURE), tagged).forEach { assertReadsAsItDoesAlone(it, floor) }
      remove(tagged)
    }

  @Test
  fun `an hdr clip after an sdr clip lands on the curve`() =
    runTest(timeout = TIMEOUT) {
      val sdr = fixture(HD_SDR_FIXTURE)
      val mixed =
        compositionOf {
          clip(MediaSource.of(sdr)) { trim(Duration.ZERO, CLIP) }
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
        }
      val graph = graphOf(mixed)
      val alone = renderedFrameOf(graphOf(sdrAlone(sdr)), 0)
      val opening = renderedFrameOf(graph, 0)
      val floor = if (MACOS) 0.0 else TONE_MAP_TOLERANCE_CODES
      EVERY_BAR.forEach { x -> assertCodes(opening.at(x, QUARTER), alone.at(x, QUARTER), "the SDR bar at $x", floor) }

      val hdr = renderedFrameOf(graph, CLIP_FRAMES).at(CENTRE, CENTRE)
      assertCodes(hdr, curveOfFixture(HdrTransfer.Hlg), "the HLG clip")
    }

  // The seed under a still goes through the head like any SDR frame before the chain swaps the photo in for it.
  @Test
  fun `a still beside an hdr clip reads what it reads on its own`() =
    runTest(timeout = TIMEOUT) {
      val photo = ImageSource.of(photoFixture("filmstrip-tone-map-still", Size(640, 360), STILL_COLOR))
      val hlg = MediaSource.of(hdrFixture(HdrTransfer.Hlg))
      val alone = renderedFrameOf(graphOf(compositionOf { image(photo, CLIP) }), 0).at(CENTRE, CENTRE)
      val stillFirst =
        compositionOf {
          image(photo, CLIP)
          clip(hlg) { trim(Duration.ZERO, CLIP) }
        }
      val stillSecond =
        compositionOf {
          clip(hlg) { trim(Duration.ZERO, CLIP) }
          image(photo, CLIP)
        }
      assertCodes(renderedFrameOf(graphOf(stillFirst), 0).at(CENTRE, CENTRE), alone, "a still opening the edit")
      assertCodes(
        renderedFrameOf(graphOf(stillSecond), CLIP_FRAMES).at(CENTRE, CENTRE),
        alone,
        "a still after the clip",
      )

      val expected = curveOfFixture(HdrTransfer.Hlg)
      assertCodes(
        renderedFrameOf(graphOf(stillFirst), CLIP_FRAMES).at(CENTRE, CENTRE),
        expected,
        "the clip after a still",
      )
      assertCodes(renderedFrameOf(graphOf(stillSecond), 0).at(CENTRE, CENTRE), expected, "the clip before a still")
    }

  @Test
  fun `a clip that is not bt709 beside an hdr clip reads what it reads on its own`() =
    runTest(timeout = TIMEOUT) {
      taggedSources().forEach { (name, primaries, matrix) ->
        val path = temporaryPath("bars-$name")
        withContext(Dispatchers.Default) { writeTaggedBars(path, CLIP_FRAMES, primaries, matrix) }
        assertReadsAsItDoesAlone(path, TONE_MAP_TOLERANCE_CODES)
        remove(path)
      }
      val ebu = temporaryPath("bars-ebu")
      withContext(Dispatchers.Default) { writeEbuBars(ebu, CLIP_FRAMES) }
      assertReadsAsItDoesAlone(ebu, TONE_MAP_TOLERANCE_CODES)
      remove(ebu)
    }

  @Test
  fun `an sd clip beside an hdr clip reads the colour it displays as`() =
    runTest(timeout = TIMEOUT) {
      val smpteC = temporaryPath("shown-smpte-c")
      val ebu = temporaryPath("shown-ebu")
      val m601 = temporaryPath("shown-m601")
      withContext(Dispatchers.Default) {
        writeTaggedBars(
          smpteC,
          CLIP_FRAMES,
          AVVideoColorPrimaries_SMPTE_C.orEmpty(),
          AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty(),
        )
        writeEbuBars(ebu, CLIP_FRAMES)
        writeTaggedBars(
          m601,
          CLIP_FRAMES,
          AVVideoColorPrimaries_ITU_R_709_2.orEmpty(),
          AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty(),
        )
      }
      listOf(smpteC to SMPTE_C_PRIMARIES, ebu to EBU_PRIMARIES, m601 to BT709_PRIMARIES).forEach { (path, primaries) ->
        val beside =
          compositionOf {
            clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
            clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) }
          }
        val drawn = renderedFrameOf(graphOf(beside), CLIP_FRAMES)
        val honest = codeFramesOf(path, 1).first()
        EVERY_BAR.forEach { x ->
          val source = honest.at(x, QUARTER)
          val shown = sdSourceDisplayed(doubleArrayOf(source.r, source.g, source.b), primaries)
          assertCodes(drawn.at(x, QUARTER), Rgb(shown[0], shown[1], shown[2]), "$path at $x", SD_SHOWN_CODES)
        }
        remove(path)
      }
    }

  // macOS's limit. Its AVFoundation pulls a BT.2020 colour outside BT.709 in under a composition naming BT.709, where
  // VideoToolbox's transfer clips it, and no compositor can reach that conversion. The simulator clips both ways.
  @Test
  fun `a colour far outside bt709 beside an hdr clip stays inside its pin`() =
    runTest(timeout = TIMEOUT) {
      val floors =
        mapOf(
          "p3" to TONE_MAP_TOLERANCE_CODES,
          "bt2020" to if (MACOS) MACOS_OUT_OF_GAMUT_CODES else TONE_MAP_TOLERANCE_CODES,
        )
      taggedSources().filter { it.first in floors }.forEach { (name, primaries, matrix) ->
        val path = temporaryPath("far-$name")
        withContext(Dispatchers.Default) { writeTaggedBars(path, CLIP_FRAMES, primaries, matrix, OUT_OF_GAMUT_BARS) }
        assertReadsAsItDoesAlone(path, floors.getValue(name))
        remove(path)
      }
    }

  /**
   * Holds every bar of [source], rendered at the join after an HDR clip, to the same bar rendered with no HDR clip in
   * the edit, within [floor] codes. An HD clip sits ahead of it both ways, so both frames scale it to one output size.
   */
  private suspend fun assertReadsAsItDoesAlone(
    source: String,
    floor: Double,
  ) {
    val mixed =
      compositionOf {
        clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
        clip(MediaSource.of(source)) { trim(Duration.ZERO, CLIP) }
      }
    val beside = renderedFrameOf(graphOf(mixed), CLIP_FRAMES)
    val alone = renderedFrameOf(graphOf(sdrOnly(source)), CLIP_FRAMES)
    EVERY_BAR.forEach { x -> assertCodes(beside.at(x, QUARTER), alone.at(x, QUARTER), "$source at $x", floor) }
  }

  private suspend fun graphOf(composition: Composition): AvComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, SPEC)) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition.toAvComposition()
      }
    }

  private fun taggedSources(): List<Triple<String, String, String>> =
    listOf(
      Triple("p3", AVVideoColorPrimaries_P3_D65.orEmpty(), AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()),
      Triple("bt2020", AVVideoColorPrimaries_ITU_R_2020.orEmpty(), AVVideoYCbCrMatrix_ITU_R_2020.orEmpty()),
      Triple("smpte-c", AVVideoColorPrimaries_SMPTE_C.orEmpty(), AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty()),
    )

  private fun sdrOnly(path: String): Composition =
    compositionOf {
      clip(MediaSource.of(fixture(HD_SDR_FIXTURE))) { trim(Duration.ZERO, CLIP) }
      clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) }
    }

  private fun sdrAlone(path: String): Composition =
    compositionOf { clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) } }

  // An effect forces the encode, since a clip already inside the height limit would copy across with the tags it has.
  private fun reencoded(path: String): Composition =
    compositionOf {
      clip(MediaSource.of(path)) {
        trim(Duration.ZERO, CLIP)
        addEffects(listOf(Invert()))
      }
    }

  private suspend fun exported(
    composition: Composition,
    name: String,
  ): String {
    val output = temporaryPath(name)
    withContext(Dispatchers.Default) {
      filmstrip
        .export(composition, SPEC, MediaSink.of(output))
        .toList()
        .last()
        .shouldBeInstanceOf<ExportStatus.Success>()
    }
    return output
  }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-tone-map-sdr-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val CENTRE = 0.5f
    const val QUARTER = 0.25f
    const val BARS = 8
    val MACOS = Platform.osFamily == OsFamily.MACOSX
    const val BT709_PRIMARIES = 1

    // One 8-bit chroma step of the clip's H.264, measured at 1.1 codes against the same frame's honest read.
    const val SD_SHOWN_CODES = 2.0

    // Measured at 20 codes on the worst bar of OUT_OF_GAMUT_BARS.
    const val MACOS_OUT_OF_GAMUT_CODES = 20.0
    const val SDR_FIXTURE = "apple_export_a.mp4"
    const val HD_SDR_FIXTURE = "apple_export_long.mp4"
    const val CLIP_FRAMES = 15
    val EVERY_BAR = (0 until BARS).map { (it + CENTRE) / BARS }
    val CLIP = 500.milliseconds
    val STILL_COLOR = Triple(170, 120, 90)
    val TIMEOUT = 3.minutes
    val SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
