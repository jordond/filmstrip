package dev.jordond.filmstrip.player

import androidx.media3.common.util.ExperimentalApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.player.contract.ChosenAudioTrackContractTest
import dev.jordond.filmstrip.player.contract.HeardTone
import dev.jordond.filmstrip.player.contract.PreviewAudioUnderTest
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.previewPlayerBuilder
import dev.jordond.filmstrip.test.ThreeAudio
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The chosen audio track contract on media3, heard in what a preview's own player hands its audio sink.
 */
@ExperimentalApi
class AndroidPreviewAudioTrackMediaTest : ChosenAudioTrackContractTest(AndroidPreviewAudio)

@ExperimentalApi
private object AndroidPreviewAudio : PreviewAudioUnderTest {
  override val source: MediaSource = androidFixture(ThreeAudio.FILE_NAME)

  override suspend fun heard(clip: Clip): HeardTone {
    val plan = planOf(clip)
    val composition = plan.resolved.toMedia3Preview(plan.bounds).composition
    val audio =
      withContext(Dispatchers.IO) {
        playedThrough(composition) { looper -> previewPlayerBuilder(contractContext(), looper) }
      }
    return HeardTone(audio.samples, audio.sampleRate)
  }

  private suspend fun planOf(clip: Clip): Media3PreviewPlan =
    withContext(Dispatchers.Default) {
      Media3PreviewPlanner(CONTRACT_COMPONENTS).plan(
        Composition(listOf(Track(listOf(clip)))),
        PreviewQualityPolicy.Full,
        ExportSpec(),
      )
    }.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan
}
