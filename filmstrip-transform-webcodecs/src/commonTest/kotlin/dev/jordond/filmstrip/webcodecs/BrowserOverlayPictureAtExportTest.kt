package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
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
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.Test

/**
 * A picture named by a URI is read by each lowering, so an export draws what the URI holds when the export starts.
 *
 * The URIs answer through [routeFetch], which is what lets the bytes behind one change between a plan and its export.
 */
class BrowserOverlayPictureAtExportTest {
  @Test
  fun aPictureChangedAfterPlanningDrawsItsNewBytesAtTheirOwnSize() =
    runTest {
      val uri = "https://filmstrip.test/changed-after-plan.png"
      routeFetch(uri, dataUriOf(OverlayPixels.pngOf(PLANNED_SIZE, AuthoredColors.MID_GREEN)))
      val badge = ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)
      val engine = engine()
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clipBytes()))))),
          effects = listOf(badge),
          audio = AudioSpec.Remove,
        )
      val plan = plannedOn(engine, composition)

      routeFetch(uri, dataUriOf(OverlayPixels.pngOf(CHANGED_SIZE, AuthoredColors.MID_GREY)))

      val frame = exported(engine, plan).last()
      val size = Size(frame.width, frame.height)
      val rect = badge.placedOn(size, CHANGED_SIZE).rectOn(size)
      val drawn = frame.at(rect.middleX, rect.middleY)
      val expected = rgbOf(AuthoredColors.MID_GREY)
      val off = maxOf(abs(drawn.red - expected.red), abs(drawn.green - expected.green), abs(drawn.blue - expected.blue))
      withClue("read $drawn where $expected was expected") { (off <= AuthoredColors.TOLERANCE) shouldBe true }
    }

  @Test
  fun twoClipsSharingAPictureReadItOncePerLowering() =
    runTest {
      val uri = "https://filmstrip.test/shared.png"
      routeFetch(uri, dataUriOf(OverlayPixels.pngOf(PLANNED_SIZE, AuthoredColors.MID_GREEN)))
      val badge = ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)
      val clip = Clip(MediaSource.Bytes(clipBytes()), effects = listOf(badge))
      val composition = EditComposition(listOf(Track(listOf(clip, clip))), audio = AudioSpec.Remove)
      val engine = engine()

      plannedOn(engine, composition)
      withClue("the first lowering") { fetchesOf(uri) shouldBe 1 }

      plannedOn(engine, composition)
      withClue("a later lowering reads it again") { fetchesOf(uri) shouldBe 2 }
    }

  @Test
  fun aLateAnswerToAnOverlappingPlanNeverReplacesWhatAnExportDraws() =
    runTest {
      val uri = "https://filmstrip.test/overlapping.png"
      routeFetch(uri, dataUriOf(OverlayPixels.pngOf(PLANNED_SIZE, AuthoredColors.MID_GREEN)))
      val badge = ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)
      val widening = WideningResolver()
      val engine = engine(widening)
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clipBytes()))))),
          effects = listOf(badge),
          audio = AudioSpec.Remove,
        )
      val plan = plannedOn(engine, composition)

      delayNextFetch(uri, LATE_MILLIS)
      widening.millis = 2L * LATE_MILLIS
      val overlapping = async(start = CoroutineStart.UNDISPATCHED) { plannedOn(engine, composition) }
      routeFetch(uri, dataUriOf(OverlayPixels.pngOf(CHANGED_SIZE, AuthoredColors.MID_GREY)))

      val frame = exported(engine, plan).last()
      overlapping.await()
      val size = Size(frame.width, frame.height)
      val rect = badge.placedOn(size, CHANGED_SIZE).rectOn(size)
      val drawn = frame.at(rect.middleX, rect.middleY)
      val expected = rgbOf(AuthoredColors.MID_GREY)
      val off = maxOf(abs(drawn.red - expected.red), abs(drawn.green - expected.green), abs(drawn.blue - expected.blue))
      withClue("read $drawn where $expected was expected") { (off <= AuthoredColors.TOLERANCE) shouldBe true }
    }

  @Test
  fun aPictureIsReadPastTheBrowsersHttpCache() =
    runTest {
      val uri = "https://filmstrip.test/revalidated.png"
      routeFetch(uri, dataUriOf(OverlayPixels.pngOf(PLANNED_SIZE, AuthoredColors.MID_GREEN)))
      val clip =
        Clip(MediaSource.Bytes(clipBytes()), effects = listOf(ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)))

      plannedOn(engine(), EditComposition(listOf(Track(listOf(clip))), audio = AudioSpec.Remove))

      fetchCacheOf(uri) shouldBe "no-cache"
    }

  @Test
  fun aPictureGoneAfterPlanningIsRefusedByName() =
    runTest {
      val uri = "https://filmstrip.test/gone-after-plan.png"
      routeFetch(uri, dataUriOf(OverlayPixels.pngOf(PLANNED_SIZE, AuthoredColors.MID_GREEN)))
      val badge = ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)
      val engine = engine()
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clipBytes()))))),
          effects = listOf(badge),
          audio = AudioSpec.Remove,
        )
      val plan = plannedOn(engine, composition)

      dropFetch(uri)

      val failure =
        engine
          .export(plan, MediaSink.Uri(""))
          .toList()
          .last()
          .shouldBeInstanceOf<ExportStatus.Failure>()
      failure.error.message shouldContain uri
    }

  @Test
  fun anAnimatedGifDrawsItsFirstFrameAtTimeZeroAndLater() =
    runTest {
      val gif = OverlayPixels.twoFrameGifOf(PLANNED_SIZE, AuthoredColors.MID_GREEN, AuthoredColors.MID_GREY)
      val uri = "https://filmstrip.test/animated.gif"
      routeFetch(uri, "data:image/gif;base64," + Base64.encode(gif))
      val badge = ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)
      val engine = engine()
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clipBytes()))))),
          effects = listOf(badge),
          audio = AudioSpec.Remove,
        )

      val frames = exported(engine, plannedOn(engine, composition))

      val size = Size(frames.first().width, frames.first().height)
      val rect = badge.placedOn(size, PLANNED_SIZE).rectOn(size)
      val expected = rgbOf(AuthoredColors.MID_GREEN)
      for (frame in listOf(frames.first(), frames.last())) {
        val drawn = frame.at(rect.middleX, rect.middleY)
        val off =
          maxOf(abs(drawn.red - expected.red), abs(drawn.green - expected.green), abs(drawn.blue - expected.blue))
        withClue("read $drawn where $expected was expected") { (off <= AuthoredColors.TOLERANCE) shouldBe true }
      }
    }

  private suspend fun clipBytes(): ByteArray =
    makeClip(width = WIDTH, height = HEIGHT, frames = FRAMES, frameRate = FRAME_RATE, color = Rgb.Blue)

  private suspend fun plannedOn(
    engine: BrowserExportEngine,
    composition: EditComposition,
  ): ExportPlan =
    when (val verdict = engine.plan(composition, SPEC)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
    }

  private suspend fun exported(
    engine: BrowserExportEngine,
    plan: ExportPlan,
  ): List<OutputFrame> {
    val statuses = engine.export(plan, MediaSink.Uri("")).toList()
    statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()?.let {
      throw AssertionError("the export failed: ${it.error.message}")
    }
    val success = statuses.filterIsInstance<ExportStatus.Success>().singleOrNull().shouldNotBeNull()
    return decodeFrames(MediaSource.Uri(success.output.shouldBeInstanceOf<MediaSink.Uri>().uri))
  }

  private fun engine(vararg ahead: EffectResolver): BrowserExportEngine {
    val components = ComponentRegistry.Builder()
    ahead.forEach { components.addEffectResolver(it) }
    return webCodecsExportEngine(
      components = components.addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    )
  }

  // Prepares after the built-in resolver and takes its time, which holds open the window between a lowering's fetch and
  // its resolve that a late answer to another lowering could land in.
  private class WideningResolver : EffectResolver {
    var millis = 0L

    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? = null

    override suspend fun prepare(composition: EditComposition) {
      if (millis > 0) withContext(Dispatchers.Default) { delay(millis) }
    }
  }

  private fun dataUriOf(png: ByteArray): String = "data:image/png;base64," + Base64.encode(png)

  private companion object {
    const val WIDTH = 256
    const val HEIGHT = 128
    const val FRAME_RATE = 25
    const val FRAMES = 6
    const val LATE_MILLIS = 400
    val PLANNED_SIZE = Size(32, 16)
    val CHANGED_SIZE = Size(16, 32)
    val SPEC = ExportSpec(videoCodec = VideoCodec.H264, frameRate = FRAME_RATE)
  }
}
