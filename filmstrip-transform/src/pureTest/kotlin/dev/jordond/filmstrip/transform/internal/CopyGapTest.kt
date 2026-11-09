package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
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
    val edit = Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)))))
    val plan = plan(edit).shouldBeInstanceOf<Verdict.Capable>().plan

    plan.path shouldBe ExportPath.Copy
    plan.copyBlockedBy shouldBe emptyList()
  }

  @Test
  fun `a track that starts late re-encodes rather than copying across its lead`() {
    val edit = Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), start = 1.seconds)))

    transcodes(edit) shouldContain CopyBlocker.TrackStartsLate
  }

  @Test
  fun `a clip with no picture beside one with re-encodes rather than copying across it`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE), fixture.audioOnlyClip(2.seconds)))))

    transcodes(edit) shouldContain CopyBlocker.MultipleClips
  }

  @Test
  fun `a second track outlasting the first re-encodes rather than copying past the picture`() {
    val bed = Track(listOf(fixture.audioOnlyClip(10.seconds)), content = TrackContent.Audio)
    val edit = Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE))), bed))

    transcodes(edit) shouldContain CopyBlocker.MultipleTracks
  }

  @Test
  fun `an edit that drops its picture re-encodes rather than copying the sound alone`() {
    val edit = Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)))), audio = AudioSpec.AudioOnly)

    transcodes(edit) shouldContain CopyBlocker.AudioSpecChanged
  }

  @Test
  fun `a composition volume below one re-encodes as a gain change alone`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)))), audio = AudioSpec.Volume(0.5f))

    transcodes(edit) shouldBe listOf(CopyBlocker.CompositionGainChanged)
  }

  @Test
  fun `a composition volume of one copies the way a track volume of one does`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)))), audio = AudioSpec.Volume(1f))
    val plan = plan(edit).shouldBeInstanceOf<Verdict.Capable>().plan

    plan.path shouldBe ExportPath.Copy
    plan.copyBlockedBy shouldBe emptyList()
  }

  @Test
  fun `a composition that removes its sound re-encodes as a stream change alone`() {
    val edit = Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)))), audio = AudioSpec.Remove)

    transcodes(edit) shouldBe listOf(CopyBlocker.AudioSpecChanged)
  }

  @Test
  fun `a lone track carrying only sound is refused rather than copied`() {
    val edit =
      Composition(listOf(Track(listOf(fixture.clip(audioRate = AUDIO_RATE)), content = TrackContent.Audio)))

    plan(edit).shouldBeInstanceOf<Verdict.Incapable>()
  }

  private fun transcodes(edit: Composition): List<CopyBlocker> {
    val plan = plan(edit).shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    return plan.copyBlockedBy
  }

  private fun plan(edit: Composition): Verdict =
    fixture
      .planner(resolvers = emptyList())
      .negotiate(
        edit,
        Ask(ExportSpec(), fixture.device(video = listOf(fixture.encoder(VideoCodec.H264))), fixture.infos(edit)),
      ).verdict

  private companion object {
    const val AUDIO_RATE = 48_000
  }
}
