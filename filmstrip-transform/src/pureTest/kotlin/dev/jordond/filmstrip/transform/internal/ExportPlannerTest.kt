package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.transform.internal.negotiation.audioFormat
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
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
  fun `an untouched single clip plans capable and copies`() {
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 44_100)))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.output.size shouldBe Size(1920, 1080)
    plan.output.videoCodec shouldBe VideoCodec.H264
    plan.output.audioCodec shouldBe AudioCodec.Aac
    plan.path shouldBe ExportPath.Copy
    plan.estimate.isCopy shouldBe true
  }

  @Test
  fun `an untouched clip copies when no muxerAccepts is supplied`() {
    val verdict = fixture.plan(composition(fixture.clip()))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Copy
  }

  // The ladder's first pick for Auto is H264, and this source is HEVC, so a plan that reported the
  // ladder rather than the source would pass this by coincidence.
  @Test
  fun `a copy reports the source's real codec rather than the ladder's pick`() {
    val verdict = fixture.plan(composition(fixture.clip(codec = "hvc1")))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  // A permissive muxerAccepts agrees to copy anything the muxer takes, which is a wider set than the
  // codecs VideoCodec and AudioCodec have members for. Transcoding instead of naming the wrong
  // thing is what keeps the plan honest when a backend's own muxerAccepts does not narrow this itself.
  @Test
  fun `a source whose codec neither enum can name transcodes rather than crashing`() {
    val verdict = fixture.plan(composition(fixture.clip(codec = "some-future-codec")))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a copy reports the source's real sample rate and channel count rather than encoder-snapped values`() {
    val device = fixture.device(audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 1)))
    val verdict = fixture.plan(composition(fixture.clip(audioRate = 37_800)), device = device)

    val format =
      verdict
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.output.audioFormat
    format?.sampleRate shouldBe 37_800
    format?.channelCount shouldBe 2
  }

  @Test
  fun `a muxerAccepts refusal on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip()), muxerAccepts = { false })

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a copy limit on an otherwise untouched clip transcodes and names its blocker`() {
    val verdict = fixture.plan(composition(fixture.clip()), copyLimit = { CopyBlocker.NonSquarePixels })

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    plan.copyBlockedBy shouldBe listOf(CopyBlocker.NonSquarePixels)
  }

  @Test
  fun `the default copy limit keeps an untouched clip on the copy path`() {
    val verdict = fixture.plan(composition(fixture.clip()))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
    plan.copyBlockedBy.shouldBeEmpty()
  }

  @Test
  fun `a copy limit with nothing to say leaves a muxer refusal as the only blocker`() {
    val edit = composition(fixture.clip())
    val asked = mutableListOf<MediaInfo>()
    val verdict =
      fixture.plan(
        edit,
        muxerAccepts = { false },
        copyLimit = {
          asked += it
          null
        },
      )

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    plan.copyBlockedBy shouldBe listOf(CopyBlocker.MuxerRefusesSource)
    asked shouldContain fixture.infos(edit).values.single()
  }

  @Test
  fun `a bitrate target on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(bitrate = Bitrate.mbps(2)))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a requested audio codec on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(audioCodec = AudioCodec.Aac))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a track with a lead-in transcodes`() {
    val verdict = fixture.plan(Composition(listOf(Track(listOf(fixture.clip()), start = 2_000.milliseconds))))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a muted clip on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip().withAudio(AudioLevel.Mute)))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a track volume level on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(Composition(listOf(Track(listOf(fixture.clip()), audio = AudioLevel.Volume(0.5f)))))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  @Test
  fun `a video-only track on an otherwise untouched clip transcodes`() {
    val verdict = fixture.plan(Composition(listOf(Track(listOf(fixture.clip()), content = TrackContent.Video))))

    verdict.shouldBeInstanceOf<Verdict.Capable>().plan.path shouldBe ExportPath.Transcode
  }

  // A copy would already have kept this untouched clip's grade with no tone map at all, so the
  // muxerAccepts refusal below is what actually forces the tone map this test is about.
  @Test
  fun `a tone-mapped hdr source that cannot copy transcodes`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg)), muxerAccepts = { false })

    verdict.shouldBeInstanceOf<Verdict.Degraded>().plan.path shouldBe ExportPath.Transcode
  }

  // The cheapest fix for HDR on a device with no HDR encoder: an untouched clip needs no encoder at
  // all, so a copy keeps the grade exactly whatever the device can do.
  @Test
  fun `an untouched hdr clip copies when the device can neither encode nor tone-map it`() {
    val verdict = fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg)), canToneMap = false)

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
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

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  @Test
  fun `a trimmed hdr clip still refuses when it can neither encode nor tone-map`() {
    val trimmed = fixture.clip(hdr = HdrTransfer.Hlg, trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds))
    val verdict = fixture.plan(composition(trimmed), canToneMap = false)

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  // A cut at zero opens on the first sample of the stream, which is always a sync sample, so the
  // copy is reachable without moving the cut and the grade rides across on it.
  @Test
  fun `an hdr clip trimmed from its start copies and keeps the grade`() {
    val trimmed = fixture.clip(hdr = HdrTransfer.Hlg, trim = TimeRange.of(Duration.ZERO, 3_000.milliseconds))
    val verdict = fixture.plan(composition(trimmed), canToneMap = false)

    verdict.shouldBeInstanceOf<Verdict.Capable>()
    verdict.plan.path shouldBe ExportPath.Copy
  }

  @Test
  fun `a resized hdr clip still refuses when it can neither encode nor tone-map`() {
    val verdict =
      fixture.plan(composition(fixture.clip(hdr = HdrTransfer.Hlg)), ExportSpec(heightPx = 720), canToneMap = false)

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
  }

  @Test
  fun `a protected source is refused before anything is decoded`() {
    val verdict = fixture.plan(composition(fixture.clip(exportable = false)))

    verdict
      .shouldBeInstanceOf<Verdict.Incapable>()
      .reasons
      .single()
      .shouldBeInstanceOf<FilmstripError.SourceNotExportable>()
  }
}
