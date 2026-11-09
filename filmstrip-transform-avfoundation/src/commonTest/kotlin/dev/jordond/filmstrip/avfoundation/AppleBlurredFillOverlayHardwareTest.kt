package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Overlays drawn over a blurred fill, exported for real.
 *
 * A blurred fill is composited in before the composition's own effects run, so every overlay step
 * works on a frame that already covers the output. These check that a composition overlay still
 * draws into a bar, and that a clip overlay pushed past its clip's edge is cut there instead of
 * moving the clip over. The bar has no color of its own to predict, so what it should read comes
 * from the same letterbox exported with no overlay at all.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleBlurredFillOverlayHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `draws a composition overlay over a blurred bar`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT)
      val badge = ImageOverlay(ImageSource.ofBytes(RED_PNG), Corner.TopStart)

      val plain = letterboxed(portrait, "blur-bar-plain")
      val box = letterboxed(portrait, "blur-bar-overlay", overlay = badge)
      val rect = badge.placedOn(box.size, RASTER).rectOn(box.size)
      withClue("the badge reaches ${rect.right}, past the bar at ${box.bar}") { (rect.right < box.bar).shouldBeTrue() }

      val bare = frameOf(plain.path, MIDDLE)
      val drawn = frameOf(box.path, MIDDLE)
      val under = bare.average(rect.middleX, rect.middleY)
      withClue("the blurred bar is already $under, so no badge shows on it") {
        (distance(under, RED) > COLOR_TOLERANCE).shouldBeTrue()
      }

      assertClose(drawn.average(rect.middleX, rect.middleY), RED, "the badge in the blurred bar")
      val below = (rect.bottom + 1f) / 2f
      assertClose(drawn.average(rect.middleX, below), bare.average(rect.middleX, below), "the bar under the badge")
      listOf(plain, box).forEach { remove(it.path) }
    }

  // Half the badge is pushed off the clip's own frame. Were the step not cut back, the clip would
  // grow by that half and be laid in wider, so the off half would land in the bar and the picture
  // would slide right by half of what it grew.
  @Test
  fun `cuts a clip overlay pushed off its clip at the clip's edge over a blurred bar`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT)
      val source = frameOf(portrait)
      val clipFrame = Size(source.width, source.height)
      val authored = ImageOverlay(ImageSource.ofBytes(RED_PNG), Corner.TopStart, scale = CLIP_BADGE_SCALE)
      val placed = authored.placedOn(clipFrame, RASTER)
      val unmoved = placed.rectOn(clipFrame)
      val push = OverlayFrame(offset = OverlayOffset(-(unmoved.left + unmoved.width / 2f), 0f))
      val badge =
        ImageOverlay(authored.image, authored.corner, scale = authored.scale, animation = OverlayAnimation { push })
      val rect = placed.animatedBy(push).rectOn(clipFrame)
      withClue("the badge at $rect does not straddle the clip's edge") {
        (rect.left < 0f && rect.right > 0f).shouldBeTrue()
      }

      val plain = letterboxed(portrait, "blur-clip-plain")
      val box = letterboxed(portrait, "blur-clip-overlay", clipOverlay = badge)
      val bare = frameOf(plain.path, MIDDLE)
      val drawn = frameOf(box.path, MIDDLE)

      // The picture spans the output's full height, so a clip's y is the output's y and only x moves.
      // Read halfway between the clip's left edge and the badge's right edge.
      assertClose(drawn.average(box.bar + rect.right * box.picture / 2f, rect.middleY), RED, "the badge on the clip")
      val beside = box.bar + rect.left * box.picture / 2f
      assertClose(drawn.average(beside, rect.middleY), bare.average(beside, rect.middleY), "the bar beside it")

      val slide = -rect.left * box.picture / 2f
      val across = PICTURE_POINTS.map { box.bar + it * box.picture }
      withClue("the picture reads the same $slide further over, so a slid clip would pass") {
        across
          .any {
            distance(bare.average(it, PICTURE_ROW), bare.average(it + slide, PICTURE_ROW)) > COLOR_TOLERANCE
          }.shouldBeTrue()
      }
      across.forEach { x ->
        assertClose(drawn.average(x, PICTURE_ROW), bare.average(x, PICTURE_ROW), "the picture at $x")
      }
      listOf(plain, box).forEach { remove(it.path) }
    }

  /**
   * Exports [portrait] letterboxed into a landscape frame over a blurred fill, with the overlays given.
   *
   * [overlay] goes on the composition and [clipOverlay] on the clip.
   */
  private suspend fun letterboxed(
    portrait: String,
    name: String,
    overlay: ImageOverlay? = null,
    clipOverlay: ImageOverlay? = null,
  ): Letterbox {
    val composition =
      compositionOf {
        clip(MediaSource.of(portrait)) {
          trim(Duration.ZERO, CLIP)
          if (clipOverlay != null) effects { add(clipOverlay) }
        }
        fill(Fill.Blur)
        effects {
          crop(AspectRatio.Landscape, Fit.Contain)
          if (overlay != null) add(overlay)
        }
      }
    val output = temporaryPath(name)
    val size =
      exported(composition, output)
        .info.video
        .shouldNotBeNull()
        .displaySize
    val source = frameOf(portrait)
    return Letterbox(output, size, source.width.toFloat() / source.height * size.height / size.width)
  }

  /**
   * A letterboxed export, where [picture] is the share of the output's width the clip covers.
   */
  private class Letterbox(
    val path: String,
    val size: Size,
    val picture: Float,
  ) {
    val bar: Float get() = (1f - picture) / 2f
  }

  private val NormalizedRect.middleX: Float get() = (left + right) / 2f

  private val NormalizedRect.middleY: Float get() = (top + bottom) / 2f

  private suspend fun exported(
    composition: Composition,
    output: String,
  ): ExportStatus.Success =
    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, SPEC, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val PORTRAIT = "apple_export_portrait.mp4"
    val TIMEOUT = 2.seconds * 60

    val SPEC = ExportSpec(heightPx = 180, bitrate = COLOR_READ_BITRATE)
    val CLIP = 1.seconds
    val MIDDLE = CLIP / 2
    const val CLIP_BADGE_SCALE = 0.5f

    // Read well below the badge, across the picture's width, where only the clip's own pixels land.
    const val PICTURE_ROW = 0.75f
    val PICTURE_POINTS = BAR_CENTRES

    val RASTER = Size(4, 2)
    val RED_PNG = solidPng(RASTER.width, RASTER.height, RED)
  }
}
