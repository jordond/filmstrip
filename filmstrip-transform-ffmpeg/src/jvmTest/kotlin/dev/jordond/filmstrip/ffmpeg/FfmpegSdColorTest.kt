package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegProber
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.support.regionAverage
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration

/**
 * An SD clip through an export, held to the colour `sdSourceDisplayed` says the clip displays as.
 *
 * A plan of one SD colour and no live pixel effect keeps its codes and tags, so the file displays the source's colour
 * through its own tags. A plan with anything else in it writes BT.709, which moves each SD clip's primaries as well as
 * its matrix. A clip with no tags stays that way, with its codes unmoved. Every read goes through one function, off the
 * file's own tags.
 *
 * The green bar is why these can fail, since the mid-range bars' primaries move by no more than the tolerance.
 */
internal class FfmpegSdColorTest : SdColorTestCase() {
  @Test
  fun `a clip tagged smpte 170m displays the same colour after an export`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("tagged.mp4", TAGS_170M)
      assertExportsDisplayed(source, SMPTE_C_PRIMARIES)
    }

  @Test
  fun `a clip tagged ebu displays the same colour after an export`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("ebu.mp4", TAGS_EBU)
      assertExportsDisplayed(source, EBU_PRIMARIES)
    }

  @Test
  fun `an untagged clip stays untagged with its codes unmoved`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("untagged.mp4", null, matrix = null)
      val output = File(folder, "untagged-out.mp4")
      fixture.exported(fixture.plannedRun(twoInverts(source), ExportSpec()), output)

      tagsOf(output) shouldBe tagsOf(source)
      BAR_CENTRES.forEach { x -> assertWithin(readOf(output, x), readOf(source, x), "the bar at $x") }
    }

  @Test
  fun `an SD clip after a BT709 clip displays the same colour in a file written BT709`() =
    runTest(timeout = TIMEOUT) {
      val bt709 = clip("first-709.mp4", TAGS_709, matrix = "bt709")
      val sd = clip("second-170m.mp4", TAGS_170M)
      val output = exportedJoin("709-then-sd.mp4", bt709, sd)

      tagsOf(output) shouldBe BT709_TAGS
      assertDisplayedAt(output, FIRST_CLIP, bt709, BT709, "the BT.709 clip")
      assertDisplayedAt(output, SECOND_CLIP, sd, SMPTE_C_PRIMARIES, "the SD clip")
    }

  @Test
  fun `a BT709 clip after an SD clip displays the same colour in a file written BT709`() =
    runTest(timeout = TIMEOUT) {
      val sd = clip("first-170m.mp4", TAGS_170M)
      val bt709 = clip("second-709.mp4", TAGS_709, matrix = "bt709")
      val output = exportedJoin("sd-then-709.mp4", sd, bt709)

      tagsOf(output) shouldBe BT709_TAGS
      assertDisplayedAt(output, FIRST_CLIP, sd, SMPTE_C_PRIMARIES, "the SD clip")
      assertDisplayedAt(output, SECOND_CLIP, bt709, BT709, "the BT.709 clip")
    }

  @Test
  fun `two SD colours in one plan each display their own in a file written BT709`() =
    runTest(timeout = TIMEOUT) {
      val ntsc = clip("first-ntsc.mp4", TAGS_170M)
      val pal = clip("second-pal.mp4", TAGS_EBU)
      val output = exportedJoin("ntsc-then-pal.mp4", ntsc, pal)

      tagsOf(output) shouldBe BT709_TAGS
      assertDisplayedAt(output, FIRST_CLIP, ntsc, SMPTE_C_PRIMARIES, "the SMPTE C clip")
      assertDisplayedAt(output, SECOND_CLIP, pal, EBU_PRIMARIES, "the EBU clip")
    }

  @Test
  fun `an SD clip beside a tone mapped HDR clip displays the same colour`() =
    runTest(timeout = TIMEOUT) {
      val hdr = hdrClip("first-hdr.mp4")
      val sd = clip("second-sd.mp4", TAGS_170M)
      val output = File(folder, "hdr-then-sd.mp4")
      fixture.exported(fixture.plannedRun(join(hdr, sd), ExportSpec(hdr = HdrMode.ToneMapToSdr)), output)

      tagsOf(output) shouldBe BT709_TAGS
      assertDisplayedAt(output, SECOND_CLIP, sd, SMPTE_C_PRIMARIES, "the SD clip")
    }

  @Test
  fun `a clip with no tags keeps its read beside an SD clip`() =
    runTest(timeout = TIMEOUT) {
      val sd = clip("first-sd.mp4", TAGS_170M)
      val untagged = clip("second-untagged.mp4", null, matrix = null)
      tagsOf(untagged) shouldBe UNKNOWN_TAGS
      val output = exportedJoin("sd-then-untagged.mp4", sd, untagged)

      assertDisplayedAt(output, FIRST_CLIP, sd, SMPTE_C_PRIMARIES, "the SD clip")
      assertDisplayedAt(output, SECOND_CLIP, untagged, BT709, "the clip with no tags")
    }

  @Test
  fun `an invert on an SD clip inverts the colour it displays and writes BT709`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("invert.mp4", TAGS_170M)
      val output = File(folder, "invert-out.mp4")
      fixture.exported(fixture.plannedRun(inverted(source), ExportSpec()), output)

      BAR_CENTRES.forEach { x -> assertWithin(readOf(output, x), invertedDisplay(source, x), "the bar at $x") }
      tagsOf(output) shouldBe BT709_TAGS
    }

  @Test
  fun `an invert on an SD clip reads the same alone and beside a BT709 clip`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("invert-sd.mp4", TAGS_170M)
      val bt709 = clip("invert-709.mp4", TAGS_709, matrix = "bt709")
      val alone = File(folder, "invert-alone.mp4")
      val beside = File(folder, "invert-beside.mp4")
      fixture.exported(fixture.plannedRun(inverted(source), ExportSpec()), alone)
      fixture.exported(
        fixture.plannedRun(
          compositionOf {
            clip(MediaSource.of(source.absolutePath)) {
              trim(Duration.ZERO, CLIP)
              addEffects(listOf(Invert()))
            }
            clip(MediaSource.of(bt709.absolutePath)) { trim(Duration.ZERO, CLIP) }
          },
          ExportSpec(),
        ),
        beside,
      )

      BAR_CENTRES.forEach { x ->
        assertWithin(readOf(alone, x), readOf(beside, x, at = FIRST_CLIP), "the bar at $x")
      }
      assertDisplayedAt(beside, SECOND_CLIP, bt709, BT709, "the BT.709 clip")
    }

  @Test
  fun `a preview of an inverted SD clip shows what its file displays`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("preview-invert.mp4", TAGS_170M)
      val frame = previewFrame(inverted(source))
      BAR_CENTRES.forEach { x -> assertWithin(frame.readAt(x), invertedDisplay(source, x), "the bar at $x") }
    }

  @Test
  fun `a preview shows an SD clip as its kept file displays it`() =
    runTest(timeout = TIMEOUT) {
      for ((name, tags, primaries) in KEPT_CLIPS) {
        val source = clip("preview-$name.mp4", tags)
        assertPreviewDisplayed(twoInverts(source), source, primaries, name)
      }
    }

  @Test
  fun `a preview of a copied SD clip moves its primaries too`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("preview-copy.mp4", TAGS_170M)
      val composition = compositionOf { clip(MediaSource.of(source.absolutePath)) }
      assertPreviewDisplayed(composition, source, SMPTE_C_PRIMARIES, "a copy")
    }

  @Test
  fun `a preview of an SD clip reads the same under every hdr mode`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("preview-modes.mp4", TAGS_170M)
      val copied = compositionOf { clip(MediaSource.of(source.absolutePath)) }
      for (mode in HdrMode.entries) {
        val spec = ExportSpec(hdr = mode)
        assertPreviewDisplayed(twoInverts(source), source, SMPTE_C_PRIMARIES, "$mode drawn", spec)
        assertPreviewDisplayed(copied, source, SMPTE_C_PRIMARIES, "$mode copied", spec)
      }
    }

  @Test
  fun `a preview of an SD clip beside a BT709 clip shows what the file displays`() =
    runTest(timeout = TIMEOUT) {
      val sd = clip("preview-first-sd.mp4", TAGS_170M)
      val bt709 = clip("preview-second-709.mp4", TAGS_709, matrix = "bt709")
      assertPreviewDisplayed(join(sd, bt709), sd, SMPTE_C_PRIMARIES, "the join")
    }

  @Test
  fun `an SD clip as a buffer displays the same colour as its file`() =
    runTest(timeout = TIMEOUT) {
      val sd = clip("buffer-sd.mp4", TAGS_170M)
      val bt709 = clip("buffer-709.mp4", TAGS_709, matrix = "bt709")
      val buffer = MediaSource.Bytes(sd.readBytes())
      val output = File(folder, "buffer-out.mp4")
      val composition =
        compositionOf {
          clip(buffer) { trim(Duration.ZERO, CLIP) }
          clip(MediaSource.of(bt709.absolutePath)) { trim(Duration.ZERO, CLIP) }
        }

      fixture.exported(fixture.plannedRun(composition, ExportSpec()), output)

      tagsOf(output) shouldBe BT709_TAGS
      assertDisplayedAt(output, FIRST_CLIP, sd, SMPTE_C_PRIMARIES, "the buffer")
      assertPreviewDisplayed(compositionOf { clip(buffer) }, sd, SMPTE_C_PRIMARIES, "a preview of the buffer")
    }

  // The probe already read the file a buffer landed on, so an SD read of that one file spawns nothing of its own.
  @Test
  fun `an SD read of a buffer lands on the file its probe read under the runtime's root`() =
    runTest(timeout = TIMEOUT) {
      val sd = clip("root-sd.mp4", TAGS_170M)
      val runtime = FfmpegRuntime.unshared(FfmpegConfig(), BytesRoot(File(folder, "root").path))
      val engine =
        FfmpegExportEngine(
          ComponentRegistry
            .Builder()
            .addEffectResolver(BuiltInEffectResolver())
            .addMediaProberFactory { FfmpegProber(runtime) }
            .build(),
          runtime,
        )

      engine
        .resolve(compositionOf { clip(MediaSource.Bytes(sd.readBytes())) }, ExportSpec())
        .shouldBeInstanceOf<ResolveResult.Resolved>()

      runtime.probeSpawns shouldBe 1
      runtime.sdColorSpawns shouldBe 0
    }

  @Test
  fun `the green bar's displayed colour sits further from its own codes than the tolerance`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("own-codes.mp4", TAGS_170M)
      val read = readOf(source, BAR_CENTRES.last())
      val shown = sdSourceDisplayed(read, SMPTE_C_PRIMARIES)
      (read.indices.maxOf { abs(read[it] - shown[it]) } * MAX_CODE > SD_CODES).shouldBeTrue()
    }

  // The colorspace filter refuses an odd picture, which only a chroma format with no subsampling can store.
  @Test
  fun `an odd sized SD clip exports and previews`() =
    runTest(timeout = TIMEOUT) {
      val source = File(folder, "odd.mp4")
      write(
        "odd.mp4",
        source,
        "color=c=${BARS.last()}:s=321x241:r=$RATE:d=$SECONDS,format=yuv444p,setparams=$TAGS_170M",
        listOf("-c:v", "libx264", "-crf", "10"),
      )
      val bt709 = clip("beside-odd.mp4", TAGS_709, matrix = "bt709")
      val output = exportedJoin("odd-out.mp4", bt709, source)
      val shown = regionAverage(source.absolutePath, ODD_READ, ODD_READ, READ_SIDE, Duration.ZERO)
      val displayed =
        sdSourceDisplayed(
          doubleArrayOf(shown.first / MAX_CODE, shown.second / MAX_CODE, shown.third / MAX_CODE),
          SMPTE_C_PRIMARIES,
        )
      assertWithin(readOf(output, 0.5, at = SECOND_CLIP), displayed, "the odd clip")

      previewFrame(compositionOf { clip(MediaSource.of(source.absolutePath)) })
    }

  // The first clip sets the frame, past the size a VideoToolbox player guesses BT.601 at. ffmpeg has no guess of its
  // own, so the second clip's codes are what comes out.
  @Test
  fun `an untagged clip scaled up keeps its codes and stays untagged`() =
    runTest(timeout = TIMEOUT) {
      val large = clip("upscale-first.mp4", null, matrix = null, height = UPSCALED_HEIGHT)
      val source = clip("upscale-second.mp4", null, matrix = null)
      val output = exportedJoin("upscaled.mp4", large, source)

      tagsOf(output) shouldBe UNKNOWN_TAGS
      BAR_CENTRES.forEach { x ->
        assertWithin(readOf(output, x, UPSCALED_HEIGHT, SECOND_CLIP), readOf(source, x), "the bar at $x")
      }
    }

  private suspend fun exportedJoin(
    name: String,
    first: File,
    second: File,
  ): File {
    val output = File(folder, name)
    fixture.exported(fixture.plannedRun(join(first, second), ExportSpec()), output)
    return output
  }

  private fun join(
    first: File,
    second: File,
  ): EditComposition =
    compositionOf {
      clip(MediaSource.of(first.absolutePath)) { trim(Duration.ZERO, CLIP) }
      clip(MediaSource.of(second.absolutePath)) { trim(Duration.ZERO, CLIP) }
    }

  private fun assertDisplayedAt(
    output: File,
    at: Duration,
    source: File,
    primaries: Int,
    label: String,
  ) {
    BAR_CENTRES.forEach { x ->
      val shown = sdSourceDisplayed(readOf(source, x), primaries)
      assertWithin(readOf(output, x, at = at), shown, "$label at bar $x")
    }
  }

  private suspend fun assertPreviewDisplayed(
    composition: EditComposition,
    source: File,
    primaries: Int,
    label: String,
    spec: ExportSpec = ExportSpec(),
  ) {
    val frame = previewFrame(composition, spec)
    BAR_CENTRES.forEach { x ->
      val shown = sdSourceDisplayed(readOf(source, x), primaries)
      assertWithin(frame.readAt(x), shown, "$label at bar $x")
    }
  }

  private fun invertedDisplay(
    source: File,
    xFraction: Double,
  ): DoubleArray = sdSourceDisplayed(readOf(source, xFraction), SMPTE_C_PRIMARIES).map { 1.0 - it }.toDoubleArray()

  private suspend fun assertExportsDisplayed(
    source: File,
    primaries: Int,
  ) {
    listOf(
      "encoded" to plainClip(source),
      "two inverts that fold to nothing" to twoInverts(source),
    ).forEach { (name, composition) ->
      val output = File(folder, "$name-${source.name}")
      fixture.exported(fixture.plannedRun(composition, ExportSpec(heightPx = SMALLER_HEIGHT)), output)

      tagsOf(output) shouldBe tagsOf(source)
      tagsOf(output).last() shouldBe tagsOf(source).first()
      BAR_CENTRES.forEach { x ->
        val shown = sdSourceDisplayed(readOf(source, x), primaries)
        val written = sdSourceDisplayed(readOf(output, x, SMALLER_HEIGHT), primaries)
        assertWithin(written, shown, "$name at bar $x")
      }
    }
  }

  private fun plainClip(source: File): EditComposition =
    compositionOf { clip(MediaSource.of(source.absolutePath)) { trim(Duration.ZERO, CLIP) } }

  private fun inverted(source: File): EditComposition =
    compositionOf {
      clip(MediaSource.of(source.absolutePath)) {
        trim(Duration.ZERO, CLIP)
        addEffects(listOf(Invert()))
      }
    }

  // The planner folds these to the identity and takes them out, so the plan reads as one with no effect.
  private fun twoInverts(source: File): EditComposition =
    compositionOf {
      clip(MediaSource.of(source.absolutePath)) {
        trim(Duration.ZERO, CLIP)
        addEffects(listOf(Invert(), Invert()))
      }
    }
}
