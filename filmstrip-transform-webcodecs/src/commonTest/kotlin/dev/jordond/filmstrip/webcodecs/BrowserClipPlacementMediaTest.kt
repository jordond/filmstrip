package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.applyTo
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.geometry.coverScale
import dev.jordond.filmstrip.transform.internal.geometry.placeIn
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.webcodecs.internal.browserRenderOf
import dev.jordond.filmstrip.webcodecs.internal.render.MAX_CANVASES
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Where the browser draws each clip's quad, which has to be exactly where the shared placement puts the clip.
 */
class BrowserClipPlacementMediaTest {
  // The crop's canvas is portrait, so drawing the content over the whole canvas would stretch it. The content is
  // letterboxed inside the canvas instead and the bars above and below it are the fill's.
  @Test
  fun aLetterboxingCropLetterboxesTheClipRatherThanStretchingIt() =
    runTest {
      val crop = Crop(AspectRatio.Portrait, Fit.Contain)
      val render = renderOf(single(WIDE, listOf(crop)))
      val content = placementOf(WIDE, listOf(listOf(crop)), Size(render.width, render.height)).drawnRect

      val clip = render.clips.single()
      clip.quadHalfW shouldBe content.width
      clip.quadHalfH shouldBe content.height
      clip.hasBars shouldBe true
    }

  // Four by five into the first clip's sixteen by nine frame, where the clip's own sides land between whole pixels.
  @Test
  fun aSecondClipIsPlacedUnderItsOwnFit() =
    runTest {
      val composition =
        Composition(
          tracks = listOf(Track(listOf(clipOf(WIDE), clipOf(PORTRAIT)))),
          audio = AudioSpec.Remove,
          fill = Fill.Solid(SLATE),
        )
      val render = renderOf(composition)
      val drawn = placeIn(PORTRAIT, Size(render.width, render.height), Fit.Contain)

      val clip = render.clips.last()
      clip.quadHalfW shouldBe drawn.width
      clip.quadHalfH shouldBe drawn.height
      clip.hasBars shouldBe true
      render.clips.first().hasBars shouldBe false
    }

  // The rect crop reads the canvas the letterboxing crop makes, bars included. Both are written on the composition and
  // run inside the clip's own draw, so the canvas is built there with the content where the shared placement puts it.
  @Test
  fun aCompositionRectCropAfterALetterboxingCropBuildsTheCanvas() =
    runTest {
      val geometry = listOf(Crop(AspectRatio.Square, Fit.Contain), CropRect(RECT))
      val composition =
        Composition(
          tracks = listOf(Track(listOf(clipOf(WIDE)))),
          effects = geometry,
          audio = AudioSpec.Remove,
          fill = Fill.Solid(SLATE),
        )
      val render = renderOf(composition)
      val placement = placementOf(WIDE, listOf(emptyList(), geometry), Size(render.width, render.height))
      val content = placement.canvases.single().contentInCanvas

      render.clips
        .single()
        .canvases
        .single()
        .content
        .toList() shouldBe
        listOf(content.left, 1f - content.bottom, content.right, 1f - content.top)
    }

  // Each letterboxing crop a rect crop reads builds a canvas, and the composite pass builds at most MAX_CANVASES of
  // them in its one shader. A clip past that is refused with the limit named.
  @Test
  fun aClipBuildingMoreCanvasesThanTheCompositeHoldsIsRefusedByName() =
    runTest {
      val effects =
        (0..MAX_CANVASES).flatMap { index ->
          val aspect = if (index % 2 == 0) AspectRatio.Square else AspectRatio(16, 9)
          listOf(Crop(aspect, Fit.Contain), CropRect(RECT))
        }
      val verdict = webCodecsFilmstrip().plan(single(WIDE, effects), SPEC).shouldBeInstanceOf<Verdict.Incapable>()

      val reason = verdict.reasons.filterIsInstance<FilmstripError.InvalidComposition>().single()
      withClue(reason.message) {
        ("builds ${MAX_CANVASES + 1} canvases" in reason.message && "at most $MAX_CANVASES" in reason.message) shouldBe
          true
      }
    }

  // A stretching crop nothing reads is folded into the clip's drawn rect, so the frame its chain leaves is the square
  // the crop before it cut. The blurred cover is that frame under the shared cover scale, not the source or the canvas.
  @Test
  fun aStretchedClipsBlurredCoverIsTheFrameItsChainLeaves() =
    runTest {
      val effects = listOf(Crop(AspectRatio.Square, Fit.Crop), Crop(AspectRatio(4, 5), Fit.Stretch))
      val composition =
        Composition(
          tracks = listOf(Track(listOf(clipOf(WIDE), clipOf(WIDE, effects)))),
          audio = AudioSpec.Remove,
          fill = Fill.Blurred(),
        )
      val render = renderOf(composition)
      val output = Size(render.width, render.height)
      val placement = placementOf(WIDE, listOf(effects), output)
      val leaves = placement.drawnSize
      val cover = coverScale(leaves, output)

      val clip = render.clips.last()
      clip.hasBars shouldBe true
      clip.quadHalfW shouldBe placement.drawnRect.width
      clip.coverHalfW shouldBe leaves.width * cover / output.width
      clip.coverHalfH shouldBe leaves.height * cover / output.height
    }

  // A cut of a 640 by 360 clip that is 640 by 359.44 pixels, so the whole pixels it keeps are the planner's to say. The
  // browser draws the clip across the frame the plan reports, with no bar, however the plan aligned that frame.
  @Test
  fun aClipCropToAFrameThatIsNotWholeFillsTheFrameThePlanReports() =
    runTest {
      val crop = Crop(AspectRatio(641, 360))
      val cut = crop.retainedRect(WIDE_CLIP).applyTo(WIDE_CLIP)
      withClue("the cut has to be a whole pixel short of a fraction for this to tell") {
        cut shouldBe Size(WIDE_CLIP.width, WIDE_CLIP.height - 1)
      }

      val render = renderOf(single(WIDE_CLIP, listOf(crop)))
      val output = Size(render.width, render.height)

      withClue("planned $output, cut $cut") {
        render.clips.single().quadHalfW shouldBe 1f
        render.clips.single().quadHalfH shouldBe 1f
        render.clips.single().hasBars shouldBe false
      }
    }

  // The canvas's aspect and the frame's height make the frame the browser renders, whichever clip is first, and it is the
  // frame the plan sizes. The first clip's quad spans the frame's edges where the plan's placement does, so no row or
  // column of the frame is left over or short. The pairs are the shapes an app's compositions failed on.
  @Test
  fun aCanvasCropAndAScaleRenderTheFrameOfTheAspectWhicheverClipComesFirst() =
    runTest {
      for (sizes in APP_PAIRS) {
        for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
          val planned = mutableSetOf<Size>()
          for (order in listOf(sizes, sizes.reversed())) {
            val effects = listOf(Crop(aspect, Fit.Contain), Scale(FRAME_HEIGHT))
            val composition =
              Composition(
                tracks = listOf(Track(order.map { clipOf(it) })),
                audio = AudioSpec.Remove,
                fill = Fill.Solid(SLATE),
                effects = effects,
              )
            val resolved =
              engine().resolve(composition, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>().composition
            val render = browserRenderOf(resolved, composition)
            val frame = resolved.output.size
            val content = placementOf(order.first(), listOf(emptyList(), effects), frame).drawnRect

            withClue("$aspect from $order") {
              Size(render.width, render.height) shouldBe frame
              render.clips.first().quadHalfW shouldBe content.width
              render.clips.first().quadHalfH shouldBe content.height
            }
            planned += frame
          }
          withClue("$aspect over both orders of $sizes planned $planned") { planned.size shouldBe 1 }
        }
      }
    }

  private suspend fun single(
    size: Size,
    effects: List<EffectSpec>,
  ): Composition =
    Composition(
      tracks = listOf(Track(listOf(clipOf(size, effects)))),
      audio = AudioSpec.Remove,
      fill = Fill.Solid(SLATE),
    )

  private suspend fun clipOf(
    size: Size,
    effects: List<EffectSpec> = emptyList(),
  ): Clip =
    Clip(
      MediaSource.Bytes(makeClip(size.width, size.height, frames = FRAMES) { _, _ -> GREY }),
      effects = effects,
    )

  private companion object {
    val WIDE = Size(64, 36)
    val WIDE_CLIP = Size(640, 360)
    val PORTRAIT = Size(32, 40)
    val GREY = Rgb(128, 128, 128)

    // The app's shapes. Its 4K clip is 1080p here: a headless browser has no H.264 encoder for a 4K frame, which the
    // plan asks it about at the first clip's size, and the shape is the same 16:9.
    val APP_PAIRS = listOf(listOf(Size(180, 320), Size(320, 180)), listOf(Size(1920, 1080), Size(640, 480)))
    const val SLATE = 0xFF204060.toInt()
    const val FRAMES = 2
    const val FRAME_HEIGHT = 324
    val RECT = NormalizedRect(0.1f, 0.2f, 0.7f, 0.9f)
    val SPEC = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None)
  }
}
