package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Negotiation is shared, but the ladder each engine tries `Auto` against is not. This asserts that
 * a device offering more than one codec resolves `Auto` to what *that engine's* ladder finds first,
 * not to some order every engine agrees on, because they do not.
 *
 * The ladders below are local copies, not read from the backends: this module cannot depend on
 * `filmstrip-transform-media3`, `-avfoundation`, `-ffmpeg` or `-webcodecs`, they depend on it. So
 * this test locks negotiation's behaviour given a ladder, and records what each engine's ladder is
 * expected to be. It cannot catch one of those backends changing its own ladder; `-avfoundation` and
 * `-ffmpeg` carry their own test against their real, registered ladder for that, and `-media3` would
 * too if it had a host test source set to put one in.
 */
class CrossEngineLadderTest {
  private val fixture = PlannerFixture()

  private val media3Ladder = listOf(VideoCodec.H264, VideoCodec.Hevc)
  private val avFoundationLadder = listOf(VideoCodec.H264, VideoCodec.Hevc)
  private val ffmpegLadder = listOf(VideoCodec.H264, VideoCodec.Hevc)
  private val webCodecsLadder = listOf(VideoCodec.H264, VideoCodec.Vp9, VideoCodec.Hevc)

  @Test
  fun `auto resolves to h264 when every ladder offers it`() {
    val device = fixture.device(video = listOf(VideoCodec.H264, VideoCodec.Hevc, VideoCodec.Vp9).map(fixture::encoder))

    resolvedCodec(media3Ladder, device) shouldBe VideoCodec.H264
    resolvedCodec(avFoundationLadder, device) shouldBe VideoCodec.H264
    resolvedCodec(ffmpegLadder, device) shouldBe VideoCodec.H264
    resolvedCodec(webCodecsLadder, device) shouldBe VideoCodec.H264
  }

  @Test
  fun `without h264 each ladder falls back to what it tries next`() {
    // media3, AVFoundation and ffmpeg agree here by coincidence, not because the ladder is shared
    // code: none of them ever offers Vp9. WebCodecs' ladder tries Vp9 before Hevc, so the one
    // engine whose ladder actually differs is the one that resolves differently.
    val device = fixture.device(video = listOf(VideoCodec.Hevc, VideoCodec.Vp9).map(fixture::encoder))

    resolvedCodec(media3Ladder, device) shouldBe VideoCodec.Hevc
    resolvedCodec(avFoundationLadder, device) shouldBe VideoCodec.Hevc
    resolvedCodec(ffmpegLadder, device) shouldBe VideoCodec.Hevc
    resolvedCodec(webCodecsLadder, device) shouldBe VideoCodec.Vp9
  }

  private fun resolvedCodec(
    ladder: List<VideoCodec>,
    device: DeviceCapabilities,
  ): VideoCodec {
    val composition =
      EditComposition(listOf(Track(listOf(fixture.clip(duration = 4_000.milliseconds, bitrate = null)))))
    val export =
      fixture
        .planner(
          resolvers = emptyList(),
          ladder = ladder,
          // This test is about what the ladder resolves an encode to, not what a copy would carry
          // across untouched, so the composition is forced onto the transcode path.
          muxerAccepts = { false },
        ).negotiate(composition, Ask(ExportSpec(), device, fixture.infos(composition)))
    return export.verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.videoCodec
  }
}
