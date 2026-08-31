package dev.jordond.filmstrip.playback

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Display
import androidx.media3.transformer.Composition
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media3.internal.toMedia3Preview
import dev.jordond.filmstrip.media3.media3Backend
import dev.jordond.filmstrip.playback.internal.Media3PlanResult
import dev.jordond.filmstrip.playback.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the preview does with a ten-bit BT.2020 PQ clip on a panel that can show one.
 *
 * The assertions cover what filmstrip decides: the lowering keeps the grade rather than tone
 * mapping it, and the frames reach a real `SurfaceView` on a real window. What the compositor and
 * the panel then do with those frames is the platform's answer, so it is read out of the system's
 * own dumps and printed alongside the run rather than asserted here.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidHdrPreviewTest {
  private val context = contractContext()

  @Test
  fun theLoweredPreviewKeepsTheGradeRatherThanToneMappingIt() =
    runTest(timeout = TIMEOUT) {
      realTime {
        val filmstrip = Filmstrip(context) { media3Backend() }
        val probed = assertIs<ProbeResult.Success>(filmstrip.probe(hdrClip()))
        assertEquals(HdrTransfer.Pq, probed.info.video?.hdrTransfer, "the fixture is not PQ")

        val planned = Media3PreviewPlanner(CONTRACT_COMPONENTS).plan(hdrComposition(), PreviewQualityPolicy.Full)
        val plan = assertIs<Media3PlanResult.Ready>(planned).plan

        assertEquals(ResolvedHdr.Keep, plan.resolved.hdr, "the preview lowering tone mapped an HDR source")
        assertEquals(
          Composition.HDR_MODE_KEEP_HDR,
          plan.resolved
            .toMedia3Preview()
            .composition.hdrMode,
          "the media3 composition the preview plays is not in keep-HDR mode",
        )
      }
    }

  @Test
  fun anHdrCompositionReachesARealSurfaceAndTheSystemSaysWhatItDidWithIt() =
    runTest(timeout = TIMEOUT) {
      realTime {
        // A device that cannot decode the fixture has nothing here to measure, so the run is
        // recorded as skipped rather than passed, which would read as covered. Every emulator image
        // is one of these.
        assumeTrue("this device has no ten-bit HEVC decoder", decodesTenBitHevc())

        var panelShowsHdr = false
        val readings =
          ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
            var host: SurfaceHostActivity? = null
            scenario.onActivity { activity -> host = activity }
            val activity = assertNotNull(host, "the host activity never started")
            assertTrue(activity.awaitSurface(), "the host window produced no surface")

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

              val display = assertNotNull(activity.display, "the host window is on no display")
              panelShowsHdr = display.mode.supportedHdrTypes.isNotEmpty()
              assertEquals(
                panelShowsHdr,
                player.features.supports(PlayerFeature.HdrPreview),
                "the engine's HdrPreview claim disagrees with what the display advertises",
              )

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
      appendLine("--- codecs and colour, from logcat ---")
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
      stream.use { input -> file.outputStream().use(input::copyTo) }
    }
    return MediaSource.of(file.path)
  }

  private fun hdrComposition(): EditComposition = EditComposition(listOf(Track(listOf(Clip(hdrClip())))))

  /**
   * Whether this device has a decoder for the ten-bit HEVC the fixture is written in.
   */
  private fun decodesTenBitHevc(): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
      !codec.isEncoder &&
        codec.supportedTypes.any { it.equals(HEVC_MIME, ignoreCase = true) } &&
        codec.getCapabilitiesForType(HEVC_MIME).profileLevels.any { it.profile in TEN_BIT_HEVC_PROFILES }
    }

  private companion object {
    val TIMEOUT: Duration = 5.minutes
    val READY_BUDGET: Duration = 30.seconds
    val POLL: Duration = 50.milliseconds

    // What a display presenting nothing but SDR reports.
    const val SDR_HEADROOM = 1f

    const val HDR_CLIP = "android_export_hdr.mp4"
    const val PACKAGE = "dev.jordond.filmstrip.playback"
    const val LAYER_CONTEXT = 12
    const val LOG_TAIL = 4000
    const val LOG_LINES = 60
    const val READING_MARKER = "===== filmstrip hdr preview readings ====="

    const val HEVC_MIME = "video/hevc"

    val TEN_BIT_HEVC_PROFILES =
      setOf(
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
      )

    val DISPLAY_KEYS = listOf("mHdr", "hdrSdrRatio", "isForceSdr", "mSupportedHdrTypes")
    val LOG_KEYS = listOf("CCodec", "MediaCodecInfo", "VideoFrameProcessor", "ColorInfo", "ataspace")
  }
}
