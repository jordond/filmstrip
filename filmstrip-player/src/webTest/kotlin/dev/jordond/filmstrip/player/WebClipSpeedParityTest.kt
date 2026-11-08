package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.ClipSpeedParityContractTest
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * The clip speed contract in the browser, where the preview and the export walk the same slots through the same
 * compositor. The fixture's own picture moves, and the export is read back one H.264 round trip away, so the comparison
 * takes the floor [WebPixelContractTest] does.
 */
class WebClipSpeedParityTest : ClipSpeedParityContractTest(WebClipParity)

/**
 * The browser's side of the suites that hold a clip's preview to its export.
 */
internal object WebClipParity : ClipParityBackend {
  private val written = mutableMapOf<EditComposition, WebExport>()

  override val filmstrip: Filmstrip = CONTRACT_FILMSTRIP

  override val fixtureClip: MediaSource = webFixtureComposition().clips.first().source

  override val frameRate: Int = FIXTURE_FRAME_RATE

  // H.264 at this frame size is hard on the caption's glyph edges. The same edit at speed 1 reads 38.6 dB on Chrome 156
  // and the slow end 37.2 dB. A frame taken one source frame off reads 27 dB, well under this floor.
  override val minPsnrDb: Double get() = CAPTION_MIN_PSNR_DB

  override val minSsim: Double get() = encodedMinSsim()

  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    BrowserPlayerEngine(
      parent = scope,
      planner = BrowserPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override suspend fun movingSource(): MediaSource = fixtureClip

  override suspend fun exportedSource(composition: EditComposition): MediaSource =
    MediaSource.Uri(exportOf(composition).url)

  override suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame = webFileFrame(exportOf(composition), position)

  /**
   * The file [composition] exports to, written once per composition.
   */
  suspend fun exportOf(composition: EditComposition): WebExport =
    written.getOrPut(composition) { webExported(composition) }

  private const val CAPTION_MIN_PSNR_DB = 36.5
}
