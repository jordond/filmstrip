package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * How the planner settles the bitrate a transcode is handed, and how [VideoCodec.MatchSource] picks its codec.
 */
class BitratePlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a transcode with no bitrate runs at the default tier`() {
    val plan = transcode(ExportSpec(frameRate = 24))

    plan.output.bitrate shouldBe resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, Size(1920, 1080), 24)
  }

  @Test
  fun `a quality tier resolves through the shared bitrate`() {
    val plan = transcode(ExportSpec(quality = VideoQuality.Low, frameRate = 60))

    plan.output.bitrate shouldBe resolveVideoBitrate(VideoQuality.Low, VideoCodec.H264, Size(1920, 1080), 60)
  }

  @Test
  fun `an asked bitrate goes to the encoder as it is`() {
    transcode(ExportSpec(bitrate = Bitrate.mbps(5), frameRate = 24)).output.bitrate shouldBe Bitrate.mbps(5)
  }

  @Test
  fun `a copy has no bitrate of its own`() {
    val plan = fixture.plan(composition(fixture.clip())).shouldBeInstanceOf<Verdict.Capable>().plan

    plan.path shouldBe ExportPath.Copy
    plan.output.bitrate.shouldBeNull()
  }

  @Test
  fun `a quality tier blocks a copy as a bitrate does`() {
    val plan = fixture.plan(composition(fixture.clip()), ExportSpec(quality = VideoQuality.Medium))

    plan.shouldBeInstanceOf<Verdict.Capable>().plan.copyBlockedBy shouldBe listOf(CopyBlocker.BitrateSet)
  }

  @Test
  fun `a bitrate and a quality together are refused`() {
    val verdict =
      fixture.plan(composition(fixture.clip()), ExportSpec(bitrate = Bitrate.mbps(5), quality = VideoQuality.Low))

    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldContain "both a bitrate and a quality"
  }

  @Test
  fun `a bitrate above the encoder's ceiling is held to it and reported`() {
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264, maxBitrate = Bitrate.mbps(4))))

    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(bitrate = Bitrate.mbps(10)), device = device)

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    degraded.plan.output.bitrate shouldBe Bitrate.mbps(4)
    val adjustment = degraded.adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.BitrateClamped
    adjustment.requested shouldBe "10 Mbps"
    adjustment.resolved shouldBe "4 Mbps"
    adjustment.message shouldContain "at most 4 Mbps"
  }

  @Test
  fun `a clamp whose two rates print alike reports both in exact bits per second`() {
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264, maxBitrate = Bitrate.mbps(4))))

    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(bitrate = Bitrate(4_004_000)), device = device)

    val adjustment = verdict.shouldBeInstanceOf<Verdict.Degraded>().adjustments.single()
    adjustment.requested shouldBe "4004000 bps"
    adjustment.resolved shouldBe "4000000 bps"
    adjustment.message shouldContain "at most 4000000 bps"
  }

  @Test
  fun `a clamp to a ceiling of a part megabit reports its decimals`() {
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264, maxBitrate = Bitrate.kbps(2500))))

    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(bitrate = Bitrate.mbps(10)), device = device)

    verdict
      .shouldBeInstanceOf<Verdict.Degraded>()
      .adjustments
      .single()
      .resolved shouldBe "2.5 Mbps"
  }

  @Test
  fun `strict refuses a bitrate above the encoder's ceiling`() {
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264, maxBitrate = Bitrate.mbps(4))))

    val verdict =
      fixture.plan(composition(fixture.clip()), ExportSpec(bitrate = Bitrate.mbps(10), strict = true), device = device)

    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldContain "at most 4 Mbps"
  }

  @Test
  fun `a transcode with no bitrate has a size estimate`() {
    val plan = transcode(ExportSpec(frameRate = 24))

    plan.estimate.outputSizeBytesMin.shouldNotBeNull()
  }

  @Test
  fun `an audio-only export settles no video bitrate`() {
    val composition = composition(fixture.clip(audioRate = 44_100)).withAudio(AudioSpec.AudioOnly)

    val plan = fixture.plan(composition).shouldBeInstanceOf<Verdict.Capable>().plan

    plan.output.bitrate.shouldBeNull()
    plan.estimate.outputSizeBytesMin.shouldBeNull()
    plan.estimate.outputSizeBytesMax.shouldBeNull()
  }

  @Test
  fun `an audio-only export is not clamped to the encoder's bitrate ceiling`() {
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264, maxBitrate = Bitrate.kbps(100))))
    val composition = composition(fixture.clip(audioRate = 44_100)).withAudio(AudioSpec.AudioOnly)

    val verdict = fixture.plan(composition, ExportSpec(strict = true), device = device)

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.bitrate
      .shouldBeNull()
  }

  @Test
  fun `match source with only stills picks from the ladder without a fallback`() {
    val stills = composition(fixture.imageClip())

    val plan = fixture.plan(stills, ExportSpec.Original).shouldBeInstanceOf<Verdict.Capable>().plan
    val strict = fixture.plan(stills, ExportSpec(videoCodec = VideoCodec.MatchSource, strict = true))

    plan.output.videoCodec shouldBe VideoCodec.H264
    strict.shouldBeInstanceOf<Verdict.Capable>()
  }

  @Test
  fun `match source re-encodes an hevc source in hevc`() {
    val plan = transcode(ExportSpec(videoCodec = VideoCodec.MatchSource, frameRate = 24), codec = "hvc1")

    plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  @Test
  fun `match source falls back when the device has no encoder for the source codec`() {
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264)))

    val verdict =
      fixture.plan(
        composition(fixture.clip(codec = "hvc1")),
        ExportSpec(videoCodec = VideoCodec.MatchSource, frameRate = 24),
        device = device,
      )

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    degraded.plan.output.videoCodec shouldBe VideoCodec.H264
    degraded.adjustments.map { it.kind } shouldContain AdjustmentKind.CodecFallback
  }

  @Test
  fun `match source does not block a copy`() {
    val verdict =
      fixture.plan(composition(fixture.clip(codec = "hvc1")), ExportSpec(videoCodec = VideoCodec.MatchSource))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
    plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  // A named frame rate blocks the copy, so each of these runs through an encoder.
  private fun transcode(
    spec: ExportSpec,
    codec: String? = null,
  ) = fixture
    .plan(composition(fixture.clip(codec = codec)), spec)
    .shouldBeInstanceOf<Verdict.Capable>()
    .plan
    .also { it.path shouldBe ExportPath.Transcode }
}
