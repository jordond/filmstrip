package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.interop.sampleTimesAround
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.segments
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Which source frame each output slot of an AVFoundation export shows, read off a clip that stamps its own index into
 * every frame.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleShownFrameMediaTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  // 29.97 fps puts every frame but the first between two ticks, and the trim opens a quarter of a frame in, so on some
  // slots the nearest frame is the one after the frame at or before the slot's source time.
  @Test
  fun `every slot at speed shows the nearest frame of a source between two ticks`() =
    runTest(timeout = TIMEOUT) {
      val written = NSTemporaryDirectory() + "filmstrip-apple-shown-ntsc.mov"
      val times = (0 until FRAMES).map { ntscTime(it) }
      withContext(Dispatchers.Default) {
        writeOffGridClip(
          written,
          frames = FRAMES,
          endsAt = ntscTime(FRAMES),
          timescale = NTSC_TIMESCALE,
          frameTime = { CMTimeMake(value = NTSC_FRAME * it, timescale = NTSC_TIMESCALE) },
        )
      }
      val start = ntscTime(1) / 4

      listOf(SLOWED, SPED_UP).forEach { speed ->
        val trim = TimeRange.of(start, ntscTime(FRAMES - 2))
        val targets = targetsOf(trim, speed)
        val atOrBefore = targets.map { target -> times.indexOfLast { it <= target } }
        withClue("no slot at $speed reads a frame other than the one at or before its source time") {
          (nearestFrames(times, targets) != atOrBefore).shouldBeTrue()
        }
        shownFrames(written, trim, speed) shouldBe nearestFrames(times, targets)
      }
      remove(written)
    }

  // Frames 20, 50 and 30 ms apart in turn, so no one rate reads every slot's nearest frame and the slots split into
  // many rows, each read on its own.
  @Test
  fun `every slot at speed shows the nearest frame of a source at a varying rate`() =
    runTest(timeout = TIMEOUT) {
      val written = NSTemporaryDirectory() + "filmstrip-apple-shown-varying.mov"
      val times = (0 until FRAMES).map { varyingTime(it) }
      withContext(Dispatchers.Default) {
        writeOffGridClip(
          written,
          frames = FRAMES,
          endsAt = varyingTime(FRAMES),
          frameTime = { varyingTime(it).toCMTime() },
        )
      }

      listOf(SLOWED, SPED_UP).forEach { speed ->
        val trim = TimeRange.of(Duration.ZERO, times[FRAMES - 2])
        shownFrames(written, trim, speed) shouldBe nearestFrames(times, targetsOf(trim, speed))
      }
      remove(written)
    }

  // 24 fps into 30 at speed 1 puts four of every five slots between two frames, so on some slots the nearest frame is
  // the one after the frame at or before the slot's time.
  @Test
  fun `every slot at speed 1 shows the nearest frame of a source at an unlike rate`() =
    runTest(timeout = TIMEOUT) {
      val written = NSTemporaryDirectory() + "filmstrip-apple-shown-film.mov"
      withContext(Dispatchers.Default) { writeFilmClip(written, AVVideoYCbCrMatrix_ITU_R_709_2) }

      shownAtSpeedOne(written, throughCompositor = false)
      remove(written)
    }

  // A BT.601 matrix sends the plan through the compositor, which draws the frame AVFoundation hands it for each slot.
  @Test
  fun `every slot at speed 1 through the sd compositor shows the nearest frame of a source at an unlike rate`() =
    runTest(timeout = TIMEOUT) {
      val written = NSTemporaryDirectory() + "filmstrip-apple-shown-film-sd.mov"
      withContext(Dispatchers.Default) { writeFilmClip(written, AVVideoYCbCrMatrix_ITU_R_601_4) }

      shownAtSpeedOne(written, throughCompositor = true)
      remove(written)
    }

  // A tone mapped frame carries no index that reads back, so the frame each slot shows is read off the laid segments.
  // The 30 fps source into 24 puts three of every four slots between two frames.
  @Test
  fun `every slot at speed 1 through the tone map lays the nearest frame of a source at an unlike rate`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)
      val trim = TimeRange.of(Duration.ZERO, 1.seconds)
      val graph = graphOf(clipOf(source, trim, 1.0), ExportSpec(frameRate = TONE_MAP_RATE, hdr = HdrMode.ToneMapToSdr))
      val times = checkNotNull(videoTrackOf(source).sampleTimesAround(Duration.ZERO, 1.seconds)).times
      val slots = slotsOf(trim, 1.0, TONE_MAP_RATE)
      val targets = slots.map { sourceTimeOfSlot(Duration.ZERO, Duration.ZERO, 1.0, it, TONE_MAP_RATE) }

      graph.drawnByCompositor.shouldBeTrue()
      withClue("no slot reads a frame other than the one at or before its source time") {
        (nearestFrames(times, targets) != targets.map { target -> times.indexOfLast { it <= target } }).shouldBeTrue()
      }
      slots.map { graph.laidFrameAt(outputSlotTime(it, TONE_MAP_RATE), times) } shouldBe nearestFrames(times, targets)
    }

  // Exports written at speed 1 and checks every slot shows its nearest frame, on the route asked for.
  private suspend fun shownAtSpeedOne(
    written: String,
    throughCompositor: Boolean,
  ) {
    val times = (0 until FRAMES).map { filmTime(it) }
    val trim = TimeRange.of(Duration.ZERO, filmTime(FRAMES - 2))
    val targets = targetsOf(trim, 1.0)
    withClue("no slot reads a frame other than the one at or before its source time") {
      (nearestFrames(times, targets) != targets.map { target -> times.indexOfLast { it <= target } }).shouldBeTrue()
    }
    graphOf(clipOf(written, trim, 1.0), ExportSpec(frameRate = RATE)).drawnByCompositor shouldBe throughCompositor
    shownFrames(written, trim, 1.0) shouldBe nearestFrames(times, targets)
  }

  private fun writeFilmClip(
    path: String,
    matrix: String?,
  ) = writeOffGridClip(path, frameRate = FILM_RATE, frames = FRAMES, endsAt = filmTime(FRAMES), matrix = matrix)

  private fun filmTime(frame: Int): Duration = 1.seconds * frame / FILM_RATE

  private suspend fun graphOf(
    composition: EditComposition,
    spec: ExportSpec,
  ): AvComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, spec)) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition.toAvComposition()
      }
    }

  private fun videoTrackOf(path: String): AVAssetTrack =
    AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
      .tracksWithMediaType(AVMediaTypeVideo)
      .single() as AVAssetTrack

  // The source frame the video track's segments show at time, the last of times at or before where it reads.
  private fun AvComposition.laidFrameAt(
    time: Duration,
    times: List<Duration>,
  ): Int {
    val track = composition.tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack
    val read =
      track.segments.filterIsInstance<AVAssetTrackSegment>().firstNotNullOf { segment ->
        segment.timeMapping.useContents {
          val opens = target.start.readValue().toDuration()
          val closes = CMTimeRangeGetEnd(target.readValue()).toDuration()
          val played = source.duration.readValue().toDuration()
          val laid = target.duration.readValue().toDuration()
          if (time < opens ||
            time >= closes
          ) {
            null
          } else {
            source.start.readValue().toDuration() + (time - opens) * (played / laid)
          }
        }
      }
    return times.indexOfLast { it <= read }
  }

  private fun targetsOf(
    trim: TimeRange,
    speed: Double,
  ): List<Duration> = slotsOf(trim, speed).map { sourceTimeOfSlot(trim.start, Duration.ZERO, speed, it, RATE) }

  private fun slotsOf(
    trim: TimeRange,
    speed: Double,
    rate: Int = RATE,
  ): LongRange = outputSlotsOf(TimeRange.of(Duration.ZERO, outputLengthOf(checkNotNull(trim.duration), speed)), rate)

  private fun clipOf(
    path: String,
    trim: TimeRange,
    speed: Double,
  ): EditComposition =
    compositionOf {
      clip(MediaSource.of(path)) {
        trim(trim)
        speed(speed)
      }
    }

  private fun nearestFrames(
    times: List<Duration>,
    targets: List<Duration>,
  ): List<Int> = targets.map { checkNotNull(nearestSourceFrame(times, it)) }

  // The frame each slot shows of an export of written, cut to trim and played at speed.
  private suspend fun shownFrames(
    written: String,
    trim: TimeRange,
    speed: Double,
  ): List<Int> {
    val composition = clipOf(written, trim, speed)
    val output = NSTemporaryDirectory() + "filmstrip-apple-shown-$speed.mp4"
    exported(composition, output)
    val shown = slotsOf(trim, speed).map { frameIndexOf(frameOf(output, outputSlotTime(it, RATE))) }
    remove(output)
    return shown
  }

  private fun varyingTime(frame: Int): Duration =
    (0 until frame).fold(Duration.ZERO) { at, index -> at + VARYING_GAPS[index % VARYING_GAPS.size] }

  private fun ntscTime(frame: Int): Duration = 1.seconds * (NTSC_FRAME * frame).toDouble() / NTSC_TIMESCALE

  private suspend fun exported(
    composition: EditComposition,
    output: String,
  ) = withContext(Dispatchers.Default) {
    val statuses = filmstrip.export(composition, ExportSpec(frameRate = RATE), MediaSink.of(output)).toList()
    when (val finished = statuses.last()) {
      is ExportStatus.Failure -> error(finished.error.message)
      else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
    }
  }

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    const val RATE = 30
    const val FILM_RATE = 24
    const val TONE_MAP_RATE = 24
    const val FRAMES = 27
    const val NTSC_TIMESCALE = 30_000
    const val NTSC_FRAME = 1_001L
    const val SLOWED = 0.7
    const val SPED_UP = 1.5
    val VARYING_GAPS = listOf(20.milliseconds, 50.milliseconds, 30.milliseconds)
  }
}
