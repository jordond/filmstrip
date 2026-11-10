package dev.jordond.filmstrip.player

import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Display
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Media3Handle
import dev.jordond.filmstrip.effects.media3WorkingLight
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.media3.media3Backend
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.internal.DISPLAY_DECIDES_HDR
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.test.OverlayPixels.pngOf
import dev.jordond.filmstrip.test.assumeDecodesTenBitHevc
import dev.jordond.filmstrip.test.assumeEncodesHdr
import dev.jordond.filmstrip.test.writeAtomically
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.media3.transformer.Composition as Media3Composition

/**
 * What the preview does with a ten-bit BT.2020 PQ clip on a panel that can show one.
 *
 * The assertions cover what filmstrip decides: the lowering keeps the grade rather than tone
 * mapping it, and the frames reach a real `SurfaceView` on a real window. What the compositor and
 * the panel then do with those frames is the platform's answer, so it is read out of the system's
 * own dumps and printed alongside the run rather than asserted here.
 */
class AndroidHdrPreviewMediaTest {
  private val context = contractContext()

  @Test
  fun theLoweredPreviewKeepsTheGradeRatherThanToneMappingIt() =
    runTest(timeout = TIMEOUT) {
      realTime {
        val filmstrip = Filmstrip(context) { media3Backend() }
        val probed = filmstrip.probe(hdrClip()).shouldBeInstanceOf<ProbeResult.Success>()
        withClue("the fixture is not PQ") { probed.info.video?.hdrTransfer shouldBe HdrTransfer.Pq }

        val planned =
          Media3PreviewPlanner(
            CONTRACT_COMPONENTS,
          ).plan(hdrComposition(), PreviewQualityPolicy.Full, ExportSpec())
        val plan = planned.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan

        withClue("the preview lowering tone mapped an HDR source") { plan.resolved.hdr shouldBe ResolvedHdr.Keep }
        withClue("the media3 composition the preview plays is not in keep-HDR mode") {
          plan.resolved
            .toMedia3Preview(plan.bounds)
            .composition.hdrMode shouldBe Media3Composition.HDR_MODE_KEEP_HDR
        }
      }
    }

  // A tone mapped frame cannot be read on the emulator, which has no ten-bit decoder, so this reads the lowering and
  // the owner's HDR phone reads the pixels. Below API 29 media3 decodes no HDR source, so the preview refuses the tone
  // map with the reason an export gives.
  @Test
  fun aToneMapSpecLowersThePreviewToTheOpenGlToneMap() =
    runTest(timeout = TIMEOUT) {
      realTime {
        val planned =
          Media3PreviewPlanner(CONTRACT_COMPONENTS).plan(
            hdrComposition(),
            PreviewQualityPolicy.Full,
            ExportSpec(hdr = HdrMode.ToneMapToSdr),
          )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
          val refused = planned.shouldBeInstanceOf<PlanResult.Refused<PlaybackError>>()
          refused.error.message shouldContain "below API 29"
          return@realTime
        }
        val plan = planned.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan

        plan.resolved.hdr shouldBe ResolvedHdr.ToneMap
        plan.info.fidelity shouldNotContain DISPLAY_DECIDES_HDR
        plan.resolved
          .toMedia3Preview(plan.bounds)
          .composition.hdrMode shouldBe Media3Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
      }
    }

  // The preview lowers through the resolver an export does, so an overlay on the kept grade carries
  // the same mapped texels at the same luminance multiplier and reports the same way. A preview
  // readback of HDR levels does not exist, so the export suite reads the levels and this reads the
  // lowering they come from.
  @Test
  fun anOverlayOnTheKeptGradeIsMappedTheWayAnExportMapsIt() =
    runTest(timeout = TIMEOUT) {
      realTime {
        // An overlay rules out a copy, so the grade is only kept where it can be encoded. Without an
        // HDR encoder an export of this composition tone maps and the preview lowers the same way,
        // which leaves no kept grade to test.
        Filmstrip(context) { media3Backend() }.assumeEncodesHdr()

        val mark = ImageOverlay(ImageSource.Bytes(pngOf(Size(MARK_PX, MARK_PX), WHITE)), Corner.BottomEnd)
        val composition = Composition(listOf(Track(listOf(Clip(hdrClip())))), listOf(mark))

        val planned =
          Media3PreviewPlanner(
            CONTRACT_COMPONENTS,
          ).plan(composition, PreviewQualityPolicy.Full, ExportSpec())
        val resolved = planned.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan.resolved

        withClue("the preview lowering tone mapped an HDR source") { resolved.hdr shouldBe ResolvedHdr.Keep }
        withClue("an overlay on the PQ preview was reported as approximated: ${resolved.adjustments}") {
          resolved.adjustments.none { it.kind == AdjustmentKind.EffectApproximated }.shouldBeTrue()
        }
        val overlay =
          resolved.compositionEffects
            .map { it.effect.handle }
            .filterIsInstance<Media3Handle.Overlay>()
            .single()
            .newOverlay()
        withClue("the preview's overlay is not drawn at the multiplier its texels were mapped against") {
          overlay.getOverlaySettings(0L).hdrLuminanceMultiplier shouldBe
            media3WorkingLight(authoredHdrNits(WHITE), HdrTransfer.Pq).max()
        }
      }
    }

  @Test
  fun anHdrCompositionReachesARealSurfaceAndTheSystemSaysWhatItDidWithIt() =
    runTest(timeout = TIMEOUT) {
      realTime {
        // The panel's HDR types and live headroom are only readable from API 34, so an older device
        // is skipped before anything is played.
        assumeTrue(
          "the display's HDR types and headroom need API 34, this device runs ${Build.VERSION.SDK_INT}",
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )
        // A device that cannot decode the fixture has nothing here to measure, so the run is
        // recorded as skipped rather than passed, which would read as covered. Every emulator image
        // is one of these.
        assumeDecodesTenBitHevc()

        var panelShowsHdr = false
        val readings =
          ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
            var host: SurfaceHostActivity? = null
            scenario.onActivity { activity -> host = activity }
            val activity = withClue("the host activity never started") { host.shouldNotBeNull() }
            withClue("the host window produced no surface") { activity.awaitSurface().shouldBeTrue() }

            val player = Filmstrip(context) { playerBackend() }.preview(hdrComposition())
            val events = mutableListOf<PlaybackEvent>()
            val collector = launch { player.events.collect { events += it } }

            try {
              val handle = player.attachPreviewSurface(activity.surfaceView)
              awaitOrFail("the preview to become ready, it sat on ${player.state.value.status}") {
                player.state.value.status == PlaybackStatus.Ready
              }

              player.play()
              awaitOrFail("a first frame to reach the surface") {
                events.any { it is PlaybackEvent.FirstFrameRendered }
              }

              val display = withClue("the host window is on no display") { activity.display.shouldNotBeNull() }
              panelShowsHdr = display.mode.supportedHdrTypes.isNotEmpty()
              withClue("the engine's HdrPreview claim disagrees with what the display advertises") {
                player.features.supports(PlayerFeature.HdrPreview) shouldBe panelShowsHdr
              }

              if (panelShowsHdr) {
                // The live headroom the compositor grants the panel. It sits at 1.0 with nothing
                // HDR on screen and rises only once an HDR layer is presented, so a graph that tone
                // mapped on the way out would leave it there.
                awaitOrFail("the display to take the layer as HDR, its headroom stayed at ${display.hdrSdrRatio}") {
                  display.hdrSdrRatio > SDR_HEADROOM
                }
              }

              systemReadings(display).also { handle.cancel() }
            } finally {
              collector.cancel()
              player.close()
            }
          }

        println(READING_MARKER)
        println(readings)
        println(READING_MARKER)

        // The headroom is what this test is for, and only a panel advertising an HDR type can
        // raise it. Recorded as skipped once the readings are out, so a run on a panel that cannot
        // present the layer does not read as one that checked that it did.
        assumeTrue("this display advertises no HDR type", panelShowsHdr)
      }
    }

  /**
   * Runs [body] off the test scheduler, so a wait spends real time rather than virtual time.
   */
  private suspend fun realTime(body: suspend () -> Unit) = withContext(Dispatchers.Default) { body() }

  private suspend fun awaitOrFail(
    description: String,
    condition: () -> Boolean,
  ) {
    val met =
      withTimeoutOrNull(READY_BUDGET) {
        while (!condition()) delay(POLL)
        true
      }
    if (met != true) fail("Timed out after $READY_BUDGET waiting for $description.")
  }

  private fun systemReadings(display: Display): String =
    buildString {
      appendLine("build: ${Build.MODEL} ${Build.DEVICE} api ${Build.VERSION.SDK_INT}")
      appendLine("displayId=${display.displayId} name=${display.name}")
      appendLine("modeSupportedHdrTypes=${display.mode.supportedHdrTypes.toList()}")
      appendLine("isHdrSdrRatioAvailable=${display.isHdrSdrRatioAvailable}")
      appendLine("hdrSdrRatio=${display.hdrSdrRatio}")
      appendLine("--- SurfaceFlinger, layers naming this package ---")
      appendLine(shell("dumpsys SurfaceFlinger").layerLines())
      appendLine("--- display devices ---")
      appendLine(shell("dumpsys display").grep(DISPLAY_KEYS))
      appendLine("--- codecs and color, from logcat ---")
      appendLine(
        shell("logcat -d -t $LOG_TAIL")
          .grep(LOG_KEYS)
          .lines()
          .takeLast(LOG_LINES)
          .joinToString("\n"),
      )
    }

  /**
   * The dump lines naming this package's layers, with whatever the compositor said about them.
   */
  private fun String.layerLines(): String {
    val lines = lineSequence().toList()
    return lines.indices
      .filter { lines[it].contains(PACKAGE) }
      .flatMap { it..minOf(it + LAYER_CONTEXT, lines.lastIndex) }
      .distinct()
      .sorted()
      .joinToString("\n") { lines[it] }
      .ifBlank { "no layer in the dump named $PACKAGE" }
  }

  private fun String.grep(keys: List<String>): String =
    lineSequence().filter { line -> keys.any(line::contains) }.joinToString("\n")

  private fun shell(command: String): String {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    return ParcelFileDescriptor
      .AutoCloseInputStream(automation.executeShellCommand(command))
      .use { it.readBytes().decodeToString() }
  }

  private fun hdrClip(): MediaSource {
    val file = File(context.cacheDir, HDR_CLIP)
    if (!file.exists()) {
      val loader = javaClass.classLoader ?: fail("the test APK has no class loader")
      val stream = loader.getResourceAsStream(HDR_CLIP) ?: fail("$HDR_CLIP was not packaged into the test APK")
      stream.use { input -> file.writeAtomically { input.copyTo(it) } }
    }
    return MediaSource.of(file.path)
  }

  private fun hdrComposition(): Composition = Composition(listOf(Track(listOf(Clip(hdrClip())))))

  private companion object {
    val TIMEOUT: Duration = 5.minutes
    val READY_BUDGET: Duration = 30.seconds
    val POLL: Duration = 50.milliseconds

    // What a display presenting nothing but SDR reports.
    const val SDR_HEADROOM = 1f

    const val HDR_CLIP = "android_export_hdr.mp4"
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MARK_PX = 16
    const val PACKAGE = "dev.jordond.filmstrip.player"
    const val LAYER_CONTEXT = 12
    const val LOG_TAIL = 4000
    const val LOG_LINES = 60
    const val READING_MARKER = "===== filmstrip hdr preview readings ====="

    val DISPLAY_KEYS = listOf("mHdr", "hdrSdrRatio", "isForceSdr", "mSupportedHdrTypes")
    val LOG_KEYS = listOf("CCodec", "MediaCodecInfo", "VideoFrameProcessor", "ColorInfo", "ataspace")
  }
}
