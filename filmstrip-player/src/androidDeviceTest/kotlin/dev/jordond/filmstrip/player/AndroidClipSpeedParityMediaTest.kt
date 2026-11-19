package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.media3Backend
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.ClipSpeedParityContractTest
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The clip speed contract on media3, where the preview and the export lower through one `toMedia3`. The frames compared
 * are one encode apart, so the comparison takes the threshold [AndroidPixelContractMediaTest] does.
 */
class AndroidClipSpeedParityMediaTest : ClipSpeedParityContractTest(AndroidClipParity)

/**
 * media3's side of the suites that hold a clip's preview to its export.
 */
internal object AndroidClipParity : ClipParityBackend {
  override val filmstrip: Filmstrip = Filmstrip(contractContext()) { media3Backend() }

  override val fixtureClip: MediaSource = androidFixtureClip()

  override val frameRate: Int = FIXTURE_FRAME_RATE

  // The encoder's limit: an export on OMX.google.h264.encoder reads as low as 0.983 on luma against the preview, where
  // c2.android.avc.encoder holds 0.990, and 32 Mbps against 8 moves the file about 1% and leaves that figure where it
  // was. The frames either side read at most 0.90, so a wrong frame still fails, and AndroidCaptionChromaMediaTest
  // bounds the colour.
  override val minSsim: Double
    get() = if (exportVideoEncoder == OMX_ENCODER) OMX_ENCODED_MIN_SSIM else ENCODED_MIN_SSIM

  // The encoder's limit: c2.android.avc.encoder writes its opening frames further off, so a slot inside them holds the
  // opening floor.
  override fun minSsimAt(slot: Long): Double = encodedMinSsimAt(slot, minSsim)

  // The encoder's limit: c2.android.avc.encoder codes a cut from a flat frame with no key frame, so a clip after a
  // solid reads 0.934 on luma against the preview on the cut and 0.957 to 0.968 for the rest of its span on the API 36
  // AVD, where the same frames of the clip exported alone agree with that export at 0.988. A frame either side reads
  // at most 0.90, so a wrong frame still fails.
  override val minSsimAfterSolid: Double get() = minOf(minSsim, AFTER_SOLID_MIN_SSIM)

  // media3's limit: the player's getFrame takes whole milliseconds, so a read that close to a frame's own time can land
  // on either side of it.
  override val previewReadSlack: Duration get() = 1.milliseconds

  // The encoder's limit: the software c2.android.avc.encoder takes each 2x2 block's chroma from its top left pixel,
  // while a preview keeps every pixel's. Luma is what the encoder leaves alone, and AndroidCaptionChromaMediaTest
  // bounds the chroma.
  override fun comparedPart(frame: TestFrame): TestFrame = frame.bt709Luma()

  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    Media3PlayerEngine(
      parent = scope,
      context = contractContext(),
      planner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override suspend fun exportedSource(composition: Composition): MediaSource =
    MediaSource.of(androidExportedFile(composition).path)

  override suspend fun exportFrame(
    composition: Composition,
    position: Duration,
  ): TestFrame = androidExportFrameCovering(composition, position)

  private const val OMX_ENCODED_MIN_SSIM = 0.98
  private const val AFTER_SOLID_MIN_SSIM = 0.92
}
