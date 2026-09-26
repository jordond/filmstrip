package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Every edit that would put a gap on the video track, planned against a backend that can copy.
 *
 * A copy writes the source's own samples and runs no frame of its own, so it has no fill to paint across a gap and no
 * pass to draw overlays over one. A backend's copy path lays no gap and a preview of it has no gap to read back, which
 * holds only while the planner never copies an edit that has one. Each case names the copy blocker that turns it away.
 */
class CopyGapTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a lone clip on a track at zero copies`() {
    val edit = EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)))))
    val plan = assertIs<Verdict.Capable>(plan(edit)).plan

    plan.path shouldBe ExportPath.Transmux
    plan.copyBlockedBy shouldBe emptyList()
  }

  @Test
  fun `a track that starts late re-encodes rather than copying across its lead`() {
    val edit = EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), start = 1.seconds)))

    transcodes(edit) shouldContain CopyBlocker.TrackStartsLate
  }

  @Test
  fun `a clip with no picture beside one with re-encodes rather than copying across it`() {
    val edit =
      EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE), fixture.audioOnlyClip(2.seconds)))))

    transcodes(edit) shouldContain CopyBlocker.MultipleClips
  }

  @Test
  fun `a second track outlasting the first re-encodes rather than copying past the picture`() {
    val bed = Track(listOf(fixture.audioOnlyClip(10.seconds)), content = TrackContent.Audio)
    val edit = EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE))), bed))

    transcodes(edit) shouldContain CopyBlocker.MultipleTracks
  }

  @Test
  fun `an edit that drops its picture re-encodes rather than copying the sound alone`() {
    val edit = EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)))), audio = AudioSpec.AudioOnly)

    transcodes(edit) shouldContain CopyBlocker.AudioSpecChanged
  }

  @Test
  fun `a lone track carrying only sound is refused rather than copied`() {
    val edit =
      EditComposition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), content = TrackContent.Audio)))

    assertIs<Verdict.Incapable>(plan(edit))
  }

  private fun transcodes(edit: EditComposition): List<CopyBlocker> {
    val plan = assertIs<Verdict.Capable>(plan(edit)).plan
    plan.path shouldBe ExportPath.Transcode
    return plan.copyBlockedBy
  }

  private fun plan(edit: EditComposition): Verdict =
    fixture
      .planner(resolvers = emptyList())
      .negotiate(
        edit,
        ExportSpec(),
        fixture.device(video = listOf(fixture.encoder(VideoCodec.H264))),
        fixture.infos(edit),
      ).verdict

  private companion object {
    const val AUDIO_RATE = 48_000
  }
}
