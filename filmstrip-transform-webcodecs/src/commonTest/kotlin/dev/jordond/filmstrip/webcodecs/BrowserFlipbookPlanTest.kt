package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.animated.atlasGrid
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.SpriteSheets
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.ATTRIBUTES
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.CLIP_LENGTH
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.FRAME_RATE
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.IMAGE
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.MAX_SIDE
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.MILLIS
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.OUTPUT_FRAME
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.RATED_SPEC
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.compositionOf
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.lower
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.preparedResolver
import dev.jordond.filmstrip.webcodecs.BrowserPlanSupport.webGl
import dev.jordond.filmstrip.webcodecs.internal.BrowserPlanner
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.browserMaxTextureSide
import dev.jordond.filmstrip.webcodecs.internal.browserParityOf
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the browser planner lowers sprite sheets and animated pictures: which cell of the one atlas each slot draws, and
 * what the planner refuses by name.
 */
class BrowserFlipbookPlanTest {
  // The flipbook picks the cell and the pass draws it, so the resolver carries the cell the shared timeline names on
  // every slot it is asked about, read in the middle of a cycle and across the wrap into the second.
  @Test
  fun aSheetDrawsTheCellTheTimelineNamesForOneDuration() =
    runTest {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME)
      val timeline = FrameTimeline.uniform(sheet.frameCount, SHEET_FRAME, sheet.plays)

      assertCellsFollow(sheet, timeline)
    }

  @Test
  fun aSheetDrawsTheCellTheTimelineNamesForUnevenDurations() =
    runTest {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)
      val timeline = FrameTimeline(SpriteSheets.UNEVEN, sheet.plays)

      assertCellsFollow(sheet, timeline)
    }

  @Test
  fun aSheetThatPlaysOnceHoldsItsLastCell() =
    runTest {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME, plays = FramePlays.Once)
      val overlay = loweredSheet(sheet)

      val last = SpriteSheets.COLUMNS * SpriteSheets.ROWS - 1
      overlay.drawAt(CLIP_LENGTH - SHEET_FRAME).shouldNotBeNull().cell shouldBe cellOf(last)
    }

  // One frame is one fixed cell, and a draw with no sheet names none.
  @Test
  fun aSheetOfOneFrameDrawsItsCellAndAPlainOverlayDrawsNone() =
    runTest {
      val one = loweredSheet(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, listOf(1.seconds)))
      for (time in listOf(Duration.ZERO, SHEET_FRAME * 2, CLIP_LENGTH / 2)) {
        withClue("at $time") { one.drawAt(time).shouldNotBeNull().cell shouldBe cellOf(0) }
      }

      val plain =
        lower(compositionOf(effects = listOf(ImageOverlay(IMAGE, Corner.TopStart))))
          .graph
          .shouldNotBeNull()
          .compositionOverlays
          .single()
      plain.drawAt(Duration.ZERO).shouldNotBeNull().cell shouldBe null
    }

  // The aspect and so the rect are the cell's, not the sheet's.
  @Test
  fun aSheetIsPlacedOnTheAspectOfItsCell() =
    runTest {
      val badge = sheetOverlay(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME))
      val overlay = loweredSheet(badge.sheet.shouldNotBeNull())

      overlay.drawAt(Duration.ZERO).shouldNotBeNull().rect shouldBe
        badge.placedOn(OUTPUT_FRAME, SpriteSheets.CELL).rectOn(OUTPUT_FRAME)
    }

  // The sheet is uploaded whole, so the page's texture side is held against the sheet and not against a cell.
  @Test
  fun aSheetPastAnInjectedTextureSideIsRefusedByName() =
    runTest {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME)
      val resolver = preparedResolver(sheetOverlay(sheet))
      val overlay = sheetOverlay(sheet)

      val side = SpriteSheets.SHEET.width - 1
      withClue("a cell is ${SpriteSheets.CELL.width} wide, so a cell side alone would fit under $side") {
        (SpriteSheets.CELL.width < side) shouldBe true
      }
      val refused =
        resolver
          .resolve(overlay, webGl(side), ATTRIBUTES)
          .shouldBeInstanceOf<EffectResolution.Unsupported>()
      refused.message shouldContain "at most $side pixels on a side"

      resolver
        .resolve(overlay, webGl(SpriteSheets.SHEET.width), ATTRIBUTES)
        .shouldBeInstanceOf<EffectResolution.Resolved>()
    }

  @Test
  fun aSheetWithNoFrameRateToCountOnIsRefusedByName() =
    runTest {
      val overlay = sheetOverlay(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME))
      val unrated =
        Attributes(
          inputSize = OUTPUT_FRAME,
          outputSize = OUTPUT_FRAME,
          layoutSize = OUTPUT_FRAME,
          colorSpace = ColorSpace.Bt709,
          hdrTransfer = null,
          frameRate = null,
          span = ATTRIBUTES.span,
        )

      val refused =
        preparedResolver(overlay)
          .resolve(overlay, webGl(MAX_SIDE), unrated)
          .shouldBeInstanceOf<EffectResolution.Unsupported>()

      refused.message shouldContain "frame rate"
    }

  @Test
  fun aSheetTheGridDoesNotDivideIsRefusedByName() =
    runTest {
      val overlay =
        ImageOverlay(
          ImageSource.ofBytes(SpriteSheets.undivisiblePng()),
          Corner.TopStart,
          sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME),
        )

      val refused =
        preparedResolver(overlay)
          .resolve(overlay, webGl(MAX_SIDE), ATTRIBUTES)
          .shouldBeInstanceOf<EffectResolution.Unsupported>()

      refused.message shouldContain "${SpriteSheets.UNDIVISIBLE.width}x${SpriteSheets.UNDIVISIBLE.height}"
    }

  // The flipbook picks the cell and the pass draws it from the one atlas, so the resolver carries the cell of the
  // shared grid that the file's own timeline names on every slot, read in the middle of a cycle and across the wrap.
  @Test
  fun anAnimatedPictureDrawsTheAtlasCellTheFilesTimelineNames() =
    runTest {
      val bytes = animatedGif()
      val picture = (animatedPictureOf(bytes) as AnimatedRead.Animated).picture
      val grid = atlasGrid(picture.frames.size, picture.canvas, null).shouldNotBeNull()
      val overlay = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)
      val lowered =
        lower(compositionOf(effects = listOf(overlay)), spec = RATED_SPEC)
          .graph
          .shouldNotBeNull()
          .compositionOverlays
          .single()
      val drawnStart = overlay.drawnRangeWithin(TimeRange(Duration.ZERO, CLIP_LENGTH)).start
      val seen = mutableSetOf<Int>()
      for (slot in 0 until CLIP_LENGTH.inWholeMilliseconds * FRAME_RATE / MILLIS) {
        for (inside in listOf(0.0, 1.0 / 3, 2.0 / 3)) {
          val time = ((slot + inside) / FRAME_RATE).seconds
          val index = picture.timeline.frameAt(drawnStart, time, FRAME_RATE)
          seen += index
          withClue("slot $slot at $time") { lowered.drawAt(time).shouldNotBeNull().cell shouldBe grid.cells[index] }
        }
      }
      withClue("the slots cover more than one frame") { (seen.size > 1) shouldBe true }
      lowered.size shouldBe grid.size
    }

  // The atlas is uploaded whole, so the page's texture side is held against the atlas and not against a frame.
  @Test
  fun anAnimatedPictureWhoseAtlasPassesAnInjectedTextureSideIsRefusedByName() =
    runTest {
      val bytes = animatedGif()
      val picture = (animatedPictureOf(bytes) as AnimatedRead.Animated).picture
      val atlas = atlasGrid(picture.frames.size, picture.canvas, null).shouldNotBeNull().size
      val side = maxOf(atlas.width, atlas.height)
      val overlay = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)
      val resolver = preparedResolver(overlay)

      withClue("a frame is ${picture.canvas.width} wide, so a frame side alone would fit under $side") {
        (picture.canvas.width < side - 1) shouldBe true
      }
      resolver
        .resolve(overlay, webGl(side - 1), ATTRIBUTES)
        .shouldBeInstanceOf<EffectResolution.Unsupported>()
        .message shouldContain "${picture.frames.size} frames"
      resolver.resolve(overlay, webGl(side), ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Resolved>()
    }

  @Test
  fun anAnimatedAvifIsRefusedByName() =
    runTest {
      val overlay = ImageOverlay(ImageSource.ofBytes(ANIMATED_AVIF), Corner.TopStart)

      preparedResolver(overlay)
        .resolve(overlay, webGl(MAX_SIDE), ATTRIBUTES)
        .shouldBeInstanceOf<EffectResolution.Unsupported>()
        .message shouldBe ANIMATED_AVIF_REFUSED
    }

  @Test
  fun aSheetOverAnAnimatedPictureIsRefusedByName() =
    runTest {
      val overlay =
        ImageOverlay(
          ImageSource.ofBytes(animatedGif()),
          Corner.TopStart,
          sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME),
        )

      preparedResolver(overlay)
        .resolve(overlay, webGl(MAX_SIDE), ATTRIBUTES)
        .shouldBeInstanceOf<EffectResolution.Unsupported>()
        .message shouldContain "sprite sheet cannot be laid over an animated picture"
    }

  @Test
  fun anAnimatedPictureWithNoFrameRateToCountOnIsRefusedByName() =
    runTest {
      val overlay = ImageOverlay(ImageSource.ofBytes(animatedGif()), Corner.TopStart)
      val unrated =
        Attributes(
          inputSize = OUTPUT_FRAME,
          outputSize = OUTPUT_FRAME,
          layoutSize = OUTPUT_FRAME,
          colorSpace = ColorSpace.Bt709,
          hdrTransfer = null,
          frameRate = null,
          span = ATTRIBUTES.span,
        )

      preparedResolver(overlay)
        .resolve(overlay, webGl(MAX_SIDE), unrated)
        .shouldBeInstanceOf<EffectResolution.Unsupported>()
        .message shouldContain "frame rate"
    }

  private fun animatedGif(): ByteArray =
    AnimatedGifs.bytes(AnimatedPictures.COUNTER_CANVAS, AnimatedPictures.counterOps(12), loops = 0)

  // Every slot of the clip, which is several passes of the durations, at a slot's own time and a third and two thirds
  // of the way through it, since a time between slots is put on the slot grid before it picks a cell.
  private suspend fun assertCellsFollow(
    sheet: SpriteSheet,
    timeline: FrameTimeline,
  ) {
    val overlay = loweredSheet(sheet)
    val span = TimeRange(Duration.ZERO, CLIP_LENGTH)
    val drawnStart = sheetOverlay(sheet).drawnRangeWithin(span).start
    val slots = CLIP_LENGTH.inWholeMilliseconds * FRAME_RATE / MILLIS
    val seen = mutableSetOf<Int>()
    for (slot in 0 until slots) {
      for (inside in listOf(0.0, 1.0 / 3, 2.0 / 3)) {
        val time = ((slot + inside) / FRAME_RATE).seconds
        val index = timeline.frameAt(drawnStart, time, FRAME_RATE)
        seen += index
        withClue("slot $slot at $time") { overlay.drawAt(time).shouldNotBeNull().cell shouldBe cellOf(index) }
      }
    }
    withClue("the slots cover more than one frame") { (seen.size > 1) shouldBe true }
  }

  private fun cellOf(index: Int): PictureCrop =
    PictureCrop(
      index % SpriteSheets.COLUMNS * SpriteSheets.CELL.width,
      index / SpriteSheets.COLUMNS * SpriteSheets.CELL.height,
      SpriteSheets.CELL,
    )

  private fun sheetOverlay(sheet: SpriteSheet): ImageOverlay =
    ImageOverlay(ImageSource.ofBytes(SpriteSheets.png()), Corner.TopStart, sheet = sheet)

  private suspend fun loweredSheet(sheet: SpriteSheet): WebGlOverlay =
    lower(compositionOf(effects = listOf(sheetOverlay(sheet))), spec = RATED_SPEC)
      .graph
      .shouldNotBeNull()
      .compositionOverlays
      .single()

  private companion object {
    val SHEET_FRAME = 150.milliseconds

    // An ftyp box whose major brand is `avis`, which is how an AVIF image sequence names itself.
    val ANIMATED_AVIF =
      byteArrayOf(0, 0, 0, 24) + "ftypavis".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) +
        "avisavif".encodeToByteArray()
  }
}
