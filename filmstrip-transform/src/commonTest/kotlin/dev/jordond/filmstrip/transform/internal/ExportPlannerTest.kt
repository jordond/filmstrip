package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * When the planner copies a clip untouched and when it transcodes it instead.
 *
 * Covers the codec and audio format a copy reports, each edit that forces an encode, hdr sources on a device that can
 * neither encode nor tone-map them, and protected sources.
 */
class ExportPlannerTest {
  private val fixture = PlannerFixture()

  @Test
  fun `an untouched single clip plans capable and transmuxes`() {
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 44_100)))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.output.size shouldBe Size(1920, 1080)
    plan.output.videoCodec shouldBe VideoCodec.H264
    plan.output.audioCodec shouldBe AudioCodec.Aac
    plan.path shouldBe ExportPath.Transmux
    plan.estimate.isPassthrough shouldBe true
  }

  @Test
  fun `an untouched clip transmuxes when no canCopy is supplied`() {
    val verdict = fixture.plan(composition(fixture.clip()))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transmux
  }

  // The ladder's first pick for Auto is H264, and this source is HEVC, so a plan that reported the
  // ladder rather than the source would pass this by coincidence.
  @Test
  fun `a copy reports the source's real codec rather than the ladder's pick`() {
    val verdict = fixture.plan(composition(fixture.clip(codec = "hvc1")))

    assertIs<Verdict.Capable>(verdict).plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  // A permissive canCopy agrees to copy anything the muxer takes, which is a wider set than the
  // codecs VideoCodec and AudioCodec have members for. Transcoding instead of naming the wrong
  // thing is what keeps the plan honest when a backend's own canCopy does not narrow this itself.
  @Test
  fun `a source whose codec neither enum can name transcodes rather than crashing`() {
    val verdict = fixture.plan(composition(fixture.clip(codec = "some-future-codec")))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a copy reports the source's real sample rate and channel count rather than encoder-snapped values`() {
    val device = fixture.device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 1)))
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 37_800)), device = device)

    val format = assertIs<Verdict.Capable>(verdict).plan.output.audioFormat
    format?.sampleRate shouldBe 37_800
    format?.channelCount shouldBe 2
  }

  @Test
  fun `a canCopy refusal on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip()), canCopy = { false })

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a bitrate target on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(bitrate = Bitrate.mbps(2)))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a requested audio codec on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(audioCodec = AudioCodec.Aac))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a track with a lead-in transcodes`() {
    val verdict = fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip()), start = 2_000.milliseconds))))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a muted clip on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip().withAudio(AudioLevel.Mute)))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a track volume level on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip()), audio = AudioLevel.Volume(0.5f)))))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a video-only track on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip()), content = TrackContent.Video))))

    assertIs<Verdict.Capable>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  // A copy would already have kept this untouched clip's grade with no tone map at all, so the
  // canCopy refusal below is what actually forces the tone map this test is about.
  @Test
  fun `a tone-mapped hdr source that cannot copy transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg)), canCopy = { false })

    assertIs<Verdict.Degraded>(verdict).plan.path shouldBe ExportPath.Transcode
  }

  // The cheapest fix for HDR on a device with no HDR encoder: an untouched clip needs no encoder at
  // all, so a copy keeps the grade exactly whatever the device can do.
  @Test
  fun `an untouched hdr clip transmuxes when the device can neither encode nor tone-map it`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg)), canToneMap = false)

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.path shouldBe ExportPath.Transmux
  }

  @Test
  fun `an untouched hdr clip keeps its grade through the copy`() {
    val resolved = fixture.resolve(composition(fixture.clip(hdr = HdrTransfer.Pq)), canToneMap = false)

    resolved.hdr shouldBe ResolvedHdr.Keep
  }

  // Test the middle of the range a copy can cover, not just its ends: any one of an effect, a trim
  // or a size change is enough on its own to take the clip off the copy path.
  @Test
  fun `an hdr clip with an effect still refuses when it can neither encode nor tone-map`() {
    val verdict =
      fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg, effects = listOf(Rotate(90)))), canToneMap = false)

    assertIs<Verdict.Incapable>(verdict)
  }

  @Test
  fun `a trimmed hdr clip still refuses when it can neither encode nor tone-map`() {
    val trimmed = fixture.clip(hdr = HdrTransfer.Hlg, trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds))
    val verdict = fixture.plan(composition(trimmed), canToneMap = false)

    assertIs<Verdict.Incapable>(verdict)
  }

  // A cut at zero opens on the first sample of the stream, which is always a sync sample, so the
  // copy is reachable without moving the cut and the grade rides across on it.
  @Test
  fun `an hdr clip trimmed from its start copies and keeps the grade`() {
    val trimmed = fixture.clip(hdr = HdrTransfer.Hlg, trim = TimeRange.of(Duration.ZERO, 3_000.milliseconds))
    val verdict = fixture.plan(composition(trimmed), canToneMap = false)

    assertIs<Verdict.Capable>(verdict)
    verdict.plan.path shouldBe ExportPath.Transmux
  }

  @Test
  fun `a resized hdr clip still refuses when it can neither encode nor tone-map`() {
    val verdict =
      fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg)), ExportSpec(targetHeight = 720), canToneMap = false)

    assertIs<Verdict.Incapable>(verdict)
  }

  @Test
  fun `a protected source is refused before anything is decoded`() {
    val verdict = fixture.plan(composition(fixture.clip(exportable = false)))

    assertIs<ExportError.SourceNotExportable>(assertIs<Verdict.Incapable>(verdict).reasons.single())
  }
}
