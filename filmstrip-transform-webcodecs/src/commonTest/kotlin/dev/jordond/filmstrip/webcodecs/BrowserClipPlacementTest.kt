package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.coverScale
import dev.jordond.filmstrip.transform.internal.geometry.placeIn
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.webcodecs.internal.render.MAX_CANVASES
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Where the browser draws each clip's quad, which has to be exactly where the shared placement puts the clip.
 */
class BrowserClipPlacementTest {
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
        EditComposition(
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
        EditComposition(
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
        EditComposition(
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

  private suspend fun single(
    size: Size,
    effects: List<EffectSpec>,
  ): EditComposition =
    EditComposition(
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
    val PORTRAIT = Size(32, 40)
    val GREY = Rgb(128, 128, 128)
    const val SLATE = 0xFF204060.toInt()
    const val FRAMES = 2
    val RECT = NormalizedRect(0.1f, 0.2f, 0.7f, 0.9f)
    val SPEC = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None)
  }
}
