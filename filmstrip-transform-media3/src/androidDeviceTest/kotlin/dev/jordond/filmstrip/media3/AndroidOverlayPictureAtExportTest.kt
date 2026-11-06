package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An overlay picture named by path is read when the export starts, whatever it held when the edit was planned.
 */
class AndroidOverlayPictureAtExportTest {
  private val probe = Media3ExportProbe()
  private val picture = File(probe.context.cacheDir, "picture-at-export.png")
  private val badge = ImageOverlay(ImageSource.of(picture.path), Corner.BottomEnd, scale = BADGE_SCALE)

  @AfterTest
  fun cleanUp() {
    picture.delete()
  }

  @Test
  fun aPictureChangedAfterPlanningDrawsItsNewBytes() =
    runTest(timeout = TIMEOUT) {
      picture.writeBytes(OverlayPixels.pngOf(PLANNED_SIZE, AuthoredColors.MID_GREEN))
      val plan = probe.plan(composition())

      picture.writeBytes(OverlayPixels.pngOf(CHANGED_SIZE, AuthoredColors.MID_GREY))

      val written = probe.writtenBy(probe.finish(plan))
      val size = written.codes.size
      val rect = badge.placedOn(size, CHANGED_SIZE).rectOn(size)
      val drawn = written.rgbAt((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
      withClue("read $drawn") {
        (distance(drawn, AuthoredColors.MID_GREY) < distance(drawn, AuthoredColors.MID_GREEN)) shouldBe true
      }
    }

  @Test
  fun aPictureDeletedAfterPlanningIsRefusedByName() =
    runTest(timeout = TIMEOUT) {
      picture.writeBytes(OverlayPixels.pngOf(PLANNED_SIZE, AuthoredColors.MID_GREEN))
      val plan = probe.plan(composition())

      picture.delete()

      val statuses = withContext(Dispatchers.Default) { probe.filmstrip.export(plan, MediaSink.temporary()).toList() }
      val refusal = statuses.last().shouldBeInstanceOf<ExportStatus.Failure>().error
      refusal.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>().message shouldContain picture.path
    }

  @Test
  fun everyEffectNamingAPictureDrawsTheOneReadItsLoweringMade() =
    runTest(timeout = TIMEOUT) {
      picture.writeBytes(OverlayPixels.pngOf(PLANNED_SIZE, AuthoredColors.MID_GREEN))
      val second = ImageOverlay(ImageSource.of(picture.path), Corner.TopStart)
      val composition = EditComposition(listOf(Track(listOf(Clip(probe.fixture())))), listOf(badge, second))
      val resolver = BuiltInEffectResolver()

      resolver.prepare(composition)
      picture.delete()

      for (effect in listOf(badge, second)) {
        resolver.resolve(effect, CAPABILITIES, ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Resolved>()
      }
      resolver.prepare(composition)
      resolver.resolve(badge, CAPABILITIES, ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Unsupported>()
    }

  @Test
  fun anAnimatedGifDrawsItsFirstFrameAtTimeZeroAndLater() =
    runTest(timeout = TIMEOUT) {
      picture.writeBytes(OverlayPixels.twoFrameGifOf(PLANNED_SIZE, AuthoredColors.MID_GREEN, AuthoredColors.MID_GREY))
      val plan = probe.plan(composition())

      val finished = probe.finish(plan)
      val written = probe.writtenBy(finished)
      val size = written.codes.size
      val rect = badge.placedOn(size, PLANNED_SIZE).rectOn(size)
      val middleX = (rect.left + rect.right) / 2f
      val middleY = (rect.top + rect.bottom) / 2f
      val zero = written.rgbAt(middleX, middleY)
      withClue("read $zero at time zero") {
        (distance(zero, AuthoredColors.MID_GREEN) < distance(zero, AuthoredColors.MID_GREY)) shouldBe true
      }
      val file = File(finished.output.shouldBeInstanceOf<MediaSink.Path>().path)
      val bitmap = probe.frame(file, GIF_LATER)
      val argb = bitmap.getPixel((middleX * bitmap.width).toInt(), (middleY * bitmap.height).toInt())
      val later = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
      withClue("read $later at $GIF_LATER") {
        (distance(later, AuthoredColors.MID_GREEN) < distance(later, AuthoredColors.MID_GREY)) shouldBe true
      }
    }

  private fun composition(): EditComposition =
    EditComposition(listOf(Track(listOf(Clip(probe.fixture())))), listOf(badge))

  private fun distance(
    drawn: Triple<Int, Int, Int>,
    argb: Int,
  ): Int {
    val (red, green, blue) = AuthoredColors.channelsOf(argb)
    return abs(drawn.first - red) + abs(drawn.second - green) + abs(drawn.third - blue)
  }

  private companion object {
    const val BADGE_SCALE = 0.3f
    val GIF_LATER = 500.milliseconds
    val CAPABILITIES =
      RenderCapabilities(RenderApi.OpenGlEs, true, false, false, setOf(ColorSpace.Bt709), 4096, emptySet())
    val ATTRIBUTES =
      Attributes(
        inputSize = Size(320, 180),
        outputSize = Size(320, 180),
        layoutSize = Size(320, 180),
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )
    val PLANNED_SIZE = Size(64, 32)
    val CHANGED_SIZE = Size(32, 64)
  }
}
