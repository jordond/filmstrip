package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class BuildersTest {
  @Test
  fun `the default media is an exportable 1080p file with one default audio track`() {
    val info = testMediaInfo()

    info.duration shouldBe 10.seconds
    info.isExportable.shouldBeTrue()
    val video = info.video.shouldNotBeNull()
    video.displaySize shouldBe Size(1920, 1080)
    video.codedSize shouldBe Size(1920, 1080)
    video.codec.kind shouldBe CodecKind.H264
    video.hdrTransfer.shouldBeNull()
    info.audioTracks shouldHaveSize 1
    info.audio shouldBe info.audioTracks.single()
    info.audio
      .shouldNotBeNull()
      .codec.kind shouldBe CodecKind.Aac
  }

  @Test
  fun `a source above 1080p keeps every other default`() {
    val info = testMediaInfo(video = testVideoTrack(displaySize = Size(3840, 2160)))

    val video = info.video.shouldNotBeNull()
    video.displaySize shouldBe Size(3840, 2160)
    video.codedSize shouldBe Size(3840, 2160)
    video.frameRate shouldBe 30f
    info.audioTracks shouldHaveSize 1
  }

  @Test
  fun `a source with no audio track has no default track either`() {
    val info = testMediaInfo(audioTracks = emptyList())

    info.audioTracks.shouldBeEmpty()
    info.audio.shouldBeNull()
    info.video.shouldNotBeNull()
  }

  @Test
  fun `a rotated track is stored on its side`() {
    val track = testVideoTrack(displaySize = Size(1080, 1920), rotationDegrees = 90)

    track.displaySize shouldBe Size(1080, 1920)
    track.codedSize shouldBe Size(1920, 1080)
    testVideoTrack(displaySize = Size(1080, 1920), rotationDegrees = 180).codedSize shouldBe Size(1080, 1920)
  }

  @Test
  fun `a plan carries the composition and spec it was made for`() {
    val composition = Composition(emptyList())
    val plan = testExportPlan(composition = composition, spec = ExportSpec.Share)

    plan.composition shouldBeSameInstanceAs composition
    plan.spec shouldBe ExportSpec.Share
    plan.path shouldBe ExportPath.Transcode
    plan.estimate.isCopy.shouldBeFalse()
    plan.output.videoCodec shouldBe VideoCodec.H264
    testExportPlan(path = ExportPath.Copy).estimate.isCopy.shouldBeTrue()
  }

  @Test
  fun `each verdict helper makes its own kind of verdict`() {
    val plan = testExportPlan()

    testCapableVerdict(plan).plan shouldBeSameInstanceAs plan
    val degraded = testDegradedVerdict(plan)
    degraded.plan shouldBeSameInstanceAs plan
    degraded.adjustments.single().kind shouldBe AdjustmentKind.ResolutionClamped
    val incapable = testIncapableVerdict(withoutUnsupported = degraded)
    incapable.reasons.single().shouldBeInstanceOf<FilmstripError.UnsupportedResolution>()
    incapable.withoutUnsupported shouldBeSameInstanceAs degraded
    testIncapableVerdict().withoutUnsupported.shouldBeNull()
  }

  @Test
  fun `sources and sinks are equal by name`() {
    testMediaSource("a") shouldBe testMediaSource("a")
    testMediaSource("a") shouldNotBe testMediaSource("b")
    testMediaSink("a") shouldBe testMediaSink("a")
    testMediaSink("a") shouldNotBe testMediaSink("b")
  }

  @Test
  fun `the default device encodes 1080p and 4K H264 and nothing else`() {
    val capabilities = testDeviceCapabilities()

    val encoder = capabilities.encoderFor(VideoCodec.H264).shouldNotBeNull()
    encoder.accepts(Size(1920, 1080)).shouldBeTrue()
    encoder.accepts(Size(3840, 2160)).shouldBeTrue()
    encoder.accepts(Size(7680, 4320)).shouldBeFalse()
    capabilities.encoderFor(VideoCodec.Hevc).shouldBeNull()
    capabilities.supportsHdrEncoding.shouldBeFalse()
  }
}
