package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.avFoundationBackend
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.ClipSpeedParityContractTest
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The clip speed contract on AVFoundation, where the preview and the export lower through one `toAvComposition`. Frames
 * are read where the encoder takes them, and a written file comes from the whole export, audio pre-stretch included.
 */
class AppleClipSpeedParityTest : ClipSpeedParityContractTest(AppleClipParity) {
  init {
    pumpMainRunLoopDuringContracts()
  }
}

/**
 * AVFoundation's side of the suites that hold a clip's preview to its export.
 */
internal object AppleClipParity : ClipParityBackend {
  private val written = mutableMapOf<EditComposition, String>()

  override val filmstrip: Filmstrip = Filmstrip { avFoundationBackend() }

  override val fixtureClip: MediaSource = MediaSource.of(appleFixtureClip())

  override val frameRate: Int = FIXTURE_FRAME_RATE

  override fun createEngine(scope: CoroutineScope): PlayerEngine =
    AvPlayerEngine(
      parent = scope,
      planner = AvPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
      muted = true,
    )

  override suspend fun exportedSource(composition: EditComposition): MediaSource =
    MediaSource.of(writtenPath(composition))

  /**
   * The path [composition] exports to at the rate its sources resolve, written once per composition.
   */
  suspend fun writtenPath(composition: EditComposition): String =
    written.getOrPut(composition) { export(composition, ExportSpec()) }

  override suspend fun exportFrame(
    composition: EditComposition,
    position: Duration,
  ): TestFrame = appleFixtureExportFrame(composition, position)

  /**
   * Writes [composition] through a real export at [spec] and hands back its path.
   */
  suspend fun export(
    composition: EditComposition,
    spec: ExportSpec,
  ): String =
    withContext(Dispatchers.Default) {
      val path = NSTemporaryDirectory() + "filmstrip-apple-speed-parity-" + NSUUID().UUIDString + ".mp4"
      val statuses = filmstrip.export(composition, spec, MediaSink.of(path)).toList()
      val finished = statuses.last()
      if (finished is ExportStatus.Failure) fail("the export failed: ${finished.error.message}")
      path
    }
}
