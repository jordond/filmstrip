package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An overlay picture named by path is read when the export starts, whatever it held when the edit was planned.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AppleOverlayPictureAtExportHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }
  private val picture = NSTemporaryDirectory() + "filmstrip-apple-picture-at-export.png"
  private val output = NSTemporaryDirectory() + "filmstrip-apple-picture-at-export.mp4"
  private val badge = ImageOverlay(ImageSource.of(picture), Corner.TopStart)

  @AfterTest
  fun cleanUp() {
    NSFileManager.defaultManager.removeItemAtPath(picture, error = null)
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
  }

  @Test
  fun `a picture changed after planning draws its new bytes`() =
    runTest(timeout = TIMEOUT) {
      write(PLANNED_SIZE, AuthoredColors.MID_GREEN)
      val plan = planned(composition())

      write(CHANGED_SIZE, AuthoredColors.MID_GREY)

      val success = statusesOf(plan).last().shouldBeInstanceOf<ExportStatus.Success>()
      val size =
        success.info.video
          .shouldNotBeNull()
          .displaySize
      val rect = badge.placedOn(size, CHANGED_SIZE).rectOn(size)
      val drawn = frameOf(output, FRAME).average((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
      withClue("read $drawn") {
        (distance(drawn, AuthoredColors.MID_GREY) < distance(drawn, AuthoredColors.MID_GREEN)) shouldBe true
      }
    }

  @Test
  fun `a picture deleted after planning is refused by name`() =
    runTest(timeout = TIMEOUT) {
      write(PLANNED_SIZE, AuthoredColors.MID_GREEN)
      val plan = planned(composition())

      NSFileManager.defaultManager.removeItemAtPath(picture, error = null)

      val refusal = statusesOf(plan).last().shouldBeInstanceOf<ExportStatus.Failure>().error
      refusal.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>().message shouldContain picture
    }

  @Test
  fun `every effect naming a picture draws the one read its lowering made`() =
    runTest(timeout = TIMEOUT) {
      write(PLANNED_SIZE, AuthoredColors.MID_GREEN)
      val second = ImageOverlay(ImageSource.of(picture), Corner.BottomEnd)
      val composition = EditComposition(emptyList(), listOf(badge, second))
      val resolver = BuiltInEffectResolver()

      resolver.prepare(composition)
      NSFileManager.defaultManager.removeItemAtPath(picture, error = null)

      for (effect in listOf(badge, second)) {
        resolver.resolve(effect, CAPABILITIES, ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Resolved>()
      }
      resolver.prepare(composition)
      resolver.resolve(badge, CAPABILITIES, ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Unsupported>()
    }

  @Test
  fun `an animated GIF draws its first frame at time zero and later`() =
    runTest(timeout = TIMEOUT) {
      val gif = OverlayPixels.twoFrameGifOf(PLANNED_SIZE, AuthoredColors.MID_GREEN, AuthoredColors.MID_GREY)
      gif.usePinned { NSData.create(bytes = it.addressOf(0), length = gif.size.toULong()) }.writeToFile(picture, true)
      val plan = planned(composition())

      val success = statusesOf(plan).last().shouldBeInstanceOf<ExportStatus.Success>()
      val size =
        success.info.video
          .shouldNotBeNull()
          .displaySize
      val rect = badge.placedOn(size, PLANNED_SIZE).rectOn(size)
      for (at in listOf(Duration.ZERO, FRAME)) {
        val drawn = frameOf(output, at).average((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
        withClue("read $drawn at $at") {
          (distance(drawn, AuthoredColors.MID_GREEN) < distance(drawn, AuthoredColors.MID_GREY)) shouldBe true
        }
      }
    }

  private fun composition(): EditComposition =
    compositionOf {
      clip(MediaSource.of(fixture("apple_export_a.mp4"))) { trim(0.seconds, 1.seconds) }
      effects { add(badge) }
    }

  private suspend fun planned(composition: EditComposition): ExportPlan =
    when (val verdict = filmstrip.plan(composition, SPEC)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
    }

  private suspend fun statusesOf(plan: ExportPlan): List<ExportStatus> =
    withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.of(output)).toList() }

  private fun write(
    size: Size,
    argb: Int,
  ) {
    val (red, green, blue) = AuthoredColors.channelsOf(argb)
    val png = solidPng(size.width, size.height, Triple(red, green, blue))
    png.usePinned { NSData.create(bytes = it.addressOf(0), length = png.size.toULong()) }.writeToFile(picture, true)
  }

  private fun distance(
    drawn: Triple<Int, Int, Int>,
    argb: Int,
  ): Int {
    val (red, green, blue) = AuthoredColors.channelsOf(argb)
    return abs(drawn.first - red) + abs(drawn.second - green) + abs(drawn.third - blue)
  }

  private companion object {
    val TIMEOUT = 120.seconds
    val CAPABILITIES =
      RenderCapabilities(RenderApi.CoreImage, true, false, false, setOf(ColorSpace.Bt709), 4096, emptySet())
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
    val FRAME = 500.milliseconds
    val SPEC = ExportSpec(heightPx = 180)
    val PLANNED_SIZE = Size(64, 32)
    val CHANGED_SIZE = Size(32, 64)
  }
}
