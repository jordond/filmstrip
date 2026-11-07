package dev.jordond.filmstrip.player

import android.os.Build
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.media3Backend
import dev.jordond.filmstrip.player.contract.ClipSpeedParityContractTest
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The clip speed contract on media3, where the preview and the export lower through one `toMedia3`. The frames compared
 * are one encode apart, so the comparison takes the threshold [AndroidPixelContractTest] does.
 */
class AndroidClipSpeedParityTest : ClipSpeedParityContractTest() {
  override val filmstrip: Filmstrip = Filmstrip(contractContext()) { media3Backend() }

  override val fixtureClip: MediaSource = androidFixtureClip()

  override val frameRate: Int = FIXTURE_FRAME_RATE

  // The encoder's limit: on API 30 c2.android.avc.encoder reads as low as 0.983 on luma against the preview, where
  // API 36 holds 0.990, and 32 Mbps against 8 moves the file about 1% and leaves that figure where it was. The frames
  // either side read at most 0.90, so a wrong frame still fails, and AndroidCaptionChromaTest bounds the colour.
  override val minSsim: Double
    get() = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) API_30_ENCODED_MIN_SSIM else ENCODED_MIN_SSIM

  // media3's limit: the player's getFrame takes whole milliseconds, so a read that close to a frame's own time can land
  // on either side of it.
  override val previewReadSlack: Duration get() = 1.milliseconds

  // The encoder's limit: the software c2.android.avc.encoder takes each 2x2 block's chroma from its top left pixel,
  // while a preview keeps every pixel's. Luma is what the encoder leaves alone, and AndroidCaptionChromaTest bounds the
  // chroma.
  override fun comparedPart(frame: TestFrame): TestFrame = frame.bt709Luma()

  // media3's limit: its last frame runs a whole source frame at the clip's speed, past a trim that cuts it short, and
  // the slowest clip here was measured running 457 ms over.
  override fun writtenLengthSlack(speed: Double): Duration =
    minOf(1.seconds / FIXTURE_FRAME_RATE / speed, 457.milliseconds)

  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    Media3PlayerEngine(
      parent = scope,
      context = contractContext(),
      planner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override suspend fun exportedSource(composition: EditComposition): MediaSource =
    MediaSource.of(androidExportedFile(composition).path)

  override suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame = androidExportFrame(composition, position)

  private companion object {
    const val API_30_ENCODED_MIN_SSIM = 0.98
  }
}
