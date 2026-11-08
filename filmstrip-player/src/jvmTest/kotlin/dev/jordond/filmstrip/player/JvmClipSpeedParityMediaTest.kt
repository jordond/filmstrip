package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.ClipSpeedParityContractTest
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.player.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The clip speed contract on ffmpeg, where the preview and the export share one graph. The export side has been through
 * libx264 and back, so the comparison takes the same thresholds [JvmPixelContractMediaTest] does.
 */
class JvmClipSpeedParityMediaTest : ClipSpeedParityContractTest(JvmClipParity) {
  // Loading a composition presents slot 0, and a read of the next slot must not be answered from it.
  @Test
  fun aReadAtTheSlotBesideTheOneShowingRendersItsOwnFrame() =
    contractTest { scope ->
      val source = movingSource()
      val trim = TimeRange.of(Duration.ZERO, 1.seconds)
      val next = outputSlotTime(1, frameRate)
      listOf(1.0, 4.0).forEach { speed ->
        val edit = EditComposition(listOf(Track(listOf(Clip(source, trim, speed = speed)))))
        val engine = createEngine(scope)
        withEngine(engine) { recorder ->
          engine.setQualityPolicy(PreviewQualityPolicy.Full)
          engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the clip at $speed to be presentable") { recorder.lastState.hasComposition }

          val shown = engine.readback.awaitFrame(next)
          val first = exportFrame(edit, Duration.ZERO)
          val second = exportFrame(edit, next)
          shown.presentationTime shouldBe next
          assertFramesSimilar(second, shown.asTestFrame(), minPsnrDb, minSsim, "slot 1 at $speed")
          assertFramesDiffer(first, second, message = "the source barely moves across one slot at $speed")
        }
      }
    }
}

/**
 * ffmpeg's side of the suites that hold a clip's preview to its export.
 */
internal object JvmClipParity : ClipParityBackend {
  private val moving = mutableMapOf<Int, MediaSource>()

  override val filmstrip: Filmstrip = Filmstrip { ffmpegBackend() }

  override val fixtureClip: MediaSource = MediaSource.of(jvmFixtureClip())

  override val frameRate: Int = FIXTURE_RATE

  override val minPsnrDb: Double get() = ENCODED_MIN_PSNR_DB

  override val minSsim: Double get() = ENCODED_MIN_SSIM

  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    FfmpegPlayerEngine(
      parent = scope,
      planner = FfmpegPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  // This backend refuses a pan, so the moving source is drawn by ffmpeg itself rather than exported.
  override suspend fun movingSource(): MediaSource = movingSourceAt(FIXTURE_RATE)

  /**
   * A moving source drawn by ffmpeg at [rate], written once per rate.
   */
  fun movingSourceAt(rate: Int): MediaSource = moving.getOrPut(rate) { drawMovingSource(rate) }

  override suspend fun exportedSource(composition: EditComposition): MediaSource =
    MediaSource.of(jvmExportedFile(composition))

  override suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame = jvmExportFrame(composition, position)

  private fun drawMovingSource(rate: Int): MediaSource {
    val file = File.createTempFile("filmstrip-jvm-moving", ".mp4").also { it.deleteOnExit() }
    val seconds = "1.5"
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-y",
        "-f",
        "lavfi",
        "-i",
        "testsrc2=size=${FIXTURE_FRAME.width}x${FIXTURE_FRAME.height}:rate=$rate:duration=$seconds",
        "-f",
        "lavfi",
        "-i",
        "sine=frequency=440:sample_rate=48000:duration=$seconds",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        file.absolutePath,
      ).redirectErrorStream(true).start()
    val log = process.inputStream.readAllBytes().decodeToString()
    if (process.waitFor() != 0) fail("writing the moving source failed: $log")
    return MediaSource.of(file.absolutePath)
  }
}
