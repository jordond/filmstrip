package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.containerExtensionOf
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.timeRange
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How one resolved track is laid onto an AVFoundation track, how its placements become spans that tile
 * the timeline, and which track holds the primary's last frame.
 */
@OptIn(ExperimentalForeignApi::class)
class AvTrackLayingTest {
  // Audio lays each clip over exactly the span the plan resolved, which is what its gain curve is
  // measured against, so a clip opening off the frame grid stays there.
  @Test
  fun `an audio track lays each clip over exactly its planned span`() {
    val source = MediaSource.of(fixture(CLIP))
    val track = laidTrack(TrackContent.Audio, OFF_GRID, source to 700.milliseconds, source to 450.milliseconds)
    val media = mediaTrack(AVMediaTypeAudio)

    val placements = track.layOnto(media, AVMediaTypeAudio, AssetCache(), frameRate = RATE, onGrid = false)

    placements.map { it.start to it.end } shouldBe
      listOf(OFF_GRID to OFF_GRID + 700.milliseconds, OFF_GRID + 700.milliseconds to OFF_GRID + 1_150.milliseconds)
    placements.forEach { it.heldFrom shouldBe null }
  }

  // On the grid each end is read back off the tick its slot is laid on, so one clip's end is the
  // next clip's start.
  @Test
  fun `a video track on the grid opens and closes each clip on its slots`() {
    val source = MediaSource.of(fixture(CLIP))
    val track = laidTrack(TrackContent.AudioAndVideo, OFF_GRID, source to 700.milliseconds, source to 450.milliseconds)

    val placements = track.layOnto(mediaTrack(AVMediaTypeVideo), AVMediaTypeVideo, AssetCache(), RATE, onGrid = true)

    placements.first().start shouldBe slotTime(OFF_GRID)
    placements[0].end shouldBe placements[1].start
    placements[0].end shouldBe slotTime(OFF_GRID + 700.milliseconds)
    placements[1].end shouldBe slotTime(OFF_GRID + 1_150.milliseconds)
  }

  @Test
  fun `a clip covering no slot on the grid takes no placement`() {
    val source = MediaSource.of(fixture(CLIP))
    val track =
      laidTrack(
        TrackContent.AudioAndVideo,
        Duration.ZERO,
        source to 1.seconds,
        source to 10.milliseconds,
        source to 1.seconds,
      )

    val placements = track.layOnto(mediaTrack(AVMediaTypeVideo), AVMediaTypeVideo, AssetCache(), RATE, onGrid = true)

    placements.map { it.clip } shouldBe listOf(track.clips[0], track.clips[2])
  }

  @Test
  fun `the last clip on the grid runs over the held tail and holds its own last slot`() {
    val source = MediaSource.of(fixture(CLIP))
    val primary = laidTrack(TrackContent.AudioAndVideo, Duration.ZERO, source to 1.seconds)
    val bed = laidTrack(TrackContent.Audio, Duration.ZERO, source to 1_500.milliseconds)
    val plan = laidPlan(listOf(primary, bed), 1_500.milliseconds)
    val tail = plan.tailHeldBy(primary)

    val held =
      primary
        .layOnto(mediaTrack(AVMediaTypeVideo), AVMediaTypeVideo, AssetCache(), RATE, onGrid = true, tail = tail)
        .single()

    held.end shouldBe outputSlotTime(tail.last + 1, RATE).asLaid()
    held.heldFrom shouldBe outputSlotTime(tail.first - 1, RATE)
  }

  // A still has no asset to read, so on the video track it takes a real segment of its own rather
  // than an empty range AVFoundation would drop off the end.
  @Test
  fun `a still on the video track takes a real segment for its whole slot`() {
    val still = stillClip(1.seconds)
    val media = mediaTrack(AVMediaTypeVideo)

    val placement =
      ResolvedTrack(TrackContent.AudioAndVideo, looping = false, start = Duration.ZERO, clips = listOf(still))
        .layOnto(media, AVMediaTypeVideo, AssetCache(), RATE, onGrid = true)
        .single()

    placement.end shouldBe slotTime(1.seconds)
    CMTimeRangeGetEnd(media.timeRange).toDuration() shouldBe placement.end
  }

  @Test
  fun `spans tile the timeline from each placement to the next and the last reaches the composition's end`() {
    val source = MediaSource.of(fixture(CLIP))
    val clip = laidClip(source, Duration.ZERO, 1.seconds)
    val gapped = listOf(Placement(clip, Duration.ZERO, 900.milliseconds), Placement(clip, 1.seconds, 2.seconds))

    val spans = gapped.toSpans(2_500.milliseconds, OUTPUT, OUTPUT, hdrTransfer = null)

    spans.map { it.start to it.end } shouldBe listOf(Duration.ZERO to 1.seconds, 1.seconds to 2_500.milliseconds)
  }

  @Test
  fun `the last span runs to its own end when that is past the composition's`() {
    val clip = laidClip(MediaSource.of(fixture(CLIP)), Duration.ZERO, 1.seconds)

    val spans = listOf(Placement(clip, Duration.ZERO, 1_200.milliseconds)).toSpans(1.seconds, OUTPUT, OUTPUT, null)

    spans.single().end shouldBe 1_200.milliseconds
  }

  @Test
  fun `only the primary laid on the grid holds a tail`() {
    val source = MediaSource.of(fixture(CLIP))
    val primary = laidTrack(TrackContent.AudioAndVideo, Duration.ZERO, source to 1.seconds)
    val bed = laidTrack(TrackContent.Audio, Duration.ZERO, source to 1_500.milliseconds)
    val plan = laidPlan(listOf(primary, bed), 1_500.milliseconds)

    (!plan.heldTail.isEmpty()).shouldBeTrue()
    plan.tailHeldBy(primary) shouldBe plan.heldTail
    plan.tailHeldBy(bed) shouldBe LongRange.EMPTY
    laidPlan(listOf(primary, bed), 1_500.milliseconds, path = ExportPath.Copy).tailHeldBy(primary) shouldBe
      LongRange.EMPTY
  }

  @Test
  fun `a source is opened once however many clips read it`() {
    val assets = AssetCache()
    val first = MediaSource.of(fixture(CLIP))
    val second = MediaSource.of(fixture(OTHER_CLIP))

    assets.of(MediaSource.of(fixture(CLIP))) shouldBeSameInstanceAs assets.of(first)
    assets.of(second) shouldNotBeSameInstanceAs assets.of(first)
  }

  @Test
  fun `a path a uri and bytes open as urls and a still is refused`() {
    MediaSource.of("/clips/a.mp4").toNSURL().path shouldBe "/clips/a.mp4"
    MediaSource.Uri("file:///clips/b.mp4").toNSURL().absoluteString shouldBe "file:///clips/b.mp4"

    val bytes = ByteArray(1)
    val written = MediaSource.Bytes(bytes).toNSURL()
    written.fileURL shouldBe true
    written.pathExtension shouldBe containerExtensionOf(bytes)

    assertFailsWith<AvLoweringFailure> { stillClip(1.seconds).source.toNSURL() }
  }

  private fun mediaTrack(mediaType: String?): AVMutableCompositionTrack =
    checkNotNull(AVMutableComposition().addMutableTrackWithMediaType(mediaType, kCMPersistentTrackID_Invalid))

  private fun stillClip(length: Duration): ResolvedClip {
    val source = MediaSource.Image(ImageSource.of("/stills/photo.png"), length)
    val info = imageMediaInfoOf(Size(640, 360), EXIF_ORIENTATION_NORMAL, "png", length)
    return ResolvedClip(
      source = source,
      info = info,
      start = Duration.ZERO,
      end = length,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, length),
      sourceIndex = 0,
    )
  }

  // Where time lands once it is on the output grid and on the composition's own tick.
  private fun slotTime(time: Duration): Duration = outputSlotTime(outputSlotOf(time, RATE), RATE).asLaid()

  private fun Duration.asLaid(): Duration = toCMTime().toDuration()

  private companion object {
    const val CLIP = "apple_export_a.mp4"
    const val OTHER_CLIP = "apple_export_b.mp4"
    const val RATE = 30
    val OUTPUT = Size(320, 180)

    // Between two slots at 30 fps, so a lay that snaps shows it.
    val OFF_GRID = 210.milliseconds
  }
}
