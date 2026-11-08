package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.avfoundation.internal.interop.pastCap
import dev.jordond.filmstrip.avfoundation.internal.interop.syncSampleAtOrBefore
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TICK
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.probe.OpeningRead
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.segments
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The snapped trim, which lets the cut move back to a sync sample so the export can stream-copy.
 *
 * The trim used here starts inside the clip and between two sync samples, so an implementation that
 * moved the cut to zero, left it where it was asked, or clamped it to the clip's ends would all read
 * differently here.
 *
 * A case fails when `FILMSTRIP_FIXTURES` is unset or names a directory missing its clip.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleFastTrimMediaTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `plans an untouched snapped trim as a copy`() =
    runTest(timeout = TIMEOUT) {
      val resolved = trimmed()

      resolved.path shouldBe ExportPath.Copy
      resolved.tracks
        .first()
        .clips
        .single()
        .startsAtKeyFrame shouldBe true
    }

  @Test
  fun `opens the cut on the sync sample at or before the trim`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture(LONG)
      val resolved = trimmed()

      val cut =
        withClue("no video cut") {
          resolved
            .toAvComposition()
            .composition
            .firstCut(AVMediaTypeVideo)
            .shouldNotBeNull()
        }
      val opening = sourceTrack(long, AVMediaTypeVideo).syncSampleAtOrBefore(TRIM_START)

      cut.start shouldBe opening
      withClue("the cut opened at ${cut.start} rather than earlier than $TRIM_START") {
        (cut.start < TRIM_START).shouldBeTrue()
      }
      withClue("the cut fell back to the start of the file instead of snapping") {
        (cut.start > Duration.ZERO).shouldBeTrue()
      }
    }

  // The cut moves back to the nearest sync sample, not merely to some earlier one, so nothing
  // between where it landed and where it was asked for can be one either. Probed in the middle of
  // that gap, which an implementation snapping to the wrong side of it would answer differently.
  @Test
  fun `moves the cut no further back than it has to`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture(LONG)
      val track = sourceTrack(long, AVMediaTypeVideo)
      val opening =
        withClue("no sync sample before $TRIM_START") {
          track.syncSampleAtOrBefore(TRIM_START).shouldNotBeNull()
        }

      track.syncSampleAtOrBefore(opening + (TRIM_START - opening) / 2) shouldBe opening
    }

  // The cut has to land on a sample a decoder can open on, not merely on an earlier one.
  @Test
  fun `opens on a sample that is itself a sync sample`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture(LONG)
      val track = sourceTrack(long, AVMediaTypeVideo)

      val opening =
        withClue("no sync sample before $TRIM_START") {
          track.syncSampleAtOrBefore(TRIM_START).shouldNotBeNull()
        }

      opening shouldBe track.syncSampleAtOrBefore(opening)
    }

  // The seam the planner reads through. It settles the opening once and every backend lays what it
  // resolved, so the driver answering anything but the track would move the cut on this backend
  // alone.
  @Test
  fun `answers the planner what the track itself answers`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture(LONG)
      val track = sourceTrack(long, AVMediaTypeVideo)

      val throughDriver =
        withContext(Dispatchers.Default) {
          driver().syncSampleAtOrBefore(MediaSource.of(long), TRIM_START)
        }.shouldBeInstanceOf<OpeningRead.At>().opening

      throughDriver shouldBe track.syncSampleAtOrBefore(TRIM_START)
    }

  // The walk from the trim back to the sync sample before it steps over more frames than this cap.
  @Test
  fun `refuses the plan when the sync sample read fails`() =
    runTest(timeout = TIMEOUT) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      val prober = chainedProber(components)
      val engine = avFoundationExportEngine(components, prober, AvDriver(prober, syncSampleCap = 1))

      val refused =
        withContext(Dispatchers.Default) { engine.resolve(snapping(fixture(LONG)), ExportSpec()) }
          .shouldBeInstanceOf<ResolveResult.Refused>()

      val error = refused.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      error.source shouldBe MediaSource.of(fixture(LONG)).describe()
      error.message shouldBe pastCap(1)
    }

  // The window ends inside a reorder group, so a frame the copy leaves out sits between the ones it writes and the
  // preview lays a cut on each side of it.
  @Test
  fun `lays a cut for each run of the copy's counted video`() =
    runTest(timeout = TIMEOUT) {
      val resolved = trimmed()

      val cuts =
        resolved
          .toAvComposition()
          .composition
          .joinedCuts(AVMediaTypeVideo)
      val runs = resolved.copyPrefix.shouldNotBeNull().runs

      withClue("the cuts ${cuts.map { it.start to it.end }} against the runs") {
        cuts.size shouldBe runs.size
        // The composition holds each end on a whole tick of its own timescale.
        cuts.zip(runs).forEach { (cut, run) ->
          ((cut.start - run.start).absoluteValue <= MEDIA_TICK).shouldBeTrue()
          ((cut.end - run.end.shouldNotBeNull()).absoluteValue <= MEDIA_TICK).shouldBeTrue()
        }
      }
    }

  @Test
  fun `cuts the audio from the same place as the picture`() =
    runTest(timeout = TIMEOUT) {
      val composition = trimmed().toAvComposition().composition

      val video = composition.firstCut(AVMediaTypeVideo)
      val audio =
        withClue("the copy wrote no audio track to keep in step with the picture") {
          composition.firstCut(AVMediaTypeAudio).shouldNotBeNull()
        }

      audio.start shouldBe video?.start
    }

  // The control. A trim the planner could not route to a copy re-encodes, and a re-encode has no
  // reason to open anywhere but where the caller asked.
  @Test
  fun `cuts exactly where asked when an effect makes it re-encode`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture(LONG)
      val graded =
        compositionOf {
          clip(MediaSource.of(long)) {
            trim(TRIM_START, TRIM_END)
            snapWithin(SNAP_WITHIN)
          }
          effects { brightness(1.2f) }
        }
      val resolved = resolved(graded)

      resolved.path shouldBe ExportPath.Transcode
      val cut = resolved.toAvComposition().composition.firstCut(AVMediaTypeVideo)
      cut?.start shouldBe TRIM_START
      cut?.end shouldBe TRIM_END
    }

  // The other control, and the default. A clip that never said the cut may move keeps it, and the
  // trim is then the term that costs the copy.
  @Test
  fun `leaves the cut where it was asked when nothing lets it move`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture(LONG)
      val asked = compositionOf { clip(MediaSource.of(long)) { trim(TRIM_START, TRIM_END) } }

      val plan = plan(asked)
      plan.path shouldBe ExportPath.Transcode
      withClue("the copy was blocked by ${plan.copyBlockedBy} rather than by the trim") {
        (CopyBlocker.TrimNotOnSyncSample in plan.copyBlockedBy).shouldBeTrue()
      }

      val resolved = resolved(asked)
      resolved.tracks
        .first()
        .clips
        .single()
        .startsAtKeyFrame shouldBe false
      resolved
        .toAvComposition()
        .composition
        .firstCut(AVMediaTypeVideo)
        ?.start shouldBe TRIM_START
    }

  // The claims above are about the graph. This one is about the file, which is the only place a cut
  // that snapped in the composition and not in the output would show.
  @Test
  fun `writes a file that runs from where the copy opened`() =
    runTest(timeout = TIMEOUT) {
      val long = fixture(LONG)
      val opening =
        withClue("no sync sample before $TRIM_START") {
          sourceTrack(long, AVMediaTypeVideo).syncSampleAtOrBefore(TRIM_START).shouldNotBeNull()
        }
      val output = NSTemporaryDirectory() + "filmstrip-apple-fast-trim.mp4"

      val written =
        withContext(Dispatchers.Default) {
          filmstrip
            .export(snapping(long), ExportSpec(), MediaSink.of(output))
            .toList()
            .last()
        }

      // The copy keeps the shared prefix of the source's packets from the opening, so the file runs to the end of the
      // last frame that prefix presents, which takes in any reference presented past the window.
      val resolved = trimmed()
      val packets = readerPacketsOf(sourceTrack(long, AVMediaTypeVideo))
      val opens = packets.indexOfFirst { it.key && it.pts == opening }
      val kept = packets.drop(opens).take(resolved.copyPrefixOf(packets).shouldBeInstanceOf<CopyPrefix.Packets>().count)
      val runs = kept.maxOf { it.pts } + 1.seconds / resolved.output.frameRate - opening

      val success = written.shouldBeInstanceOf<ExportStatus.Success>()
      val length = success.info.duration
      withClue("the file runs $length, and the copy opened at $opening so it should run $runs") {
        ((length - runs).absoluteValue <= FRAME).shouldBeTrue()
      }
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    }

  /**
   * The trimmed edit every copy claim reads, which lets the cut move far enough back to reach the
   * fixture's sync samples.
   */
  private fun snapping(source: String): EditComposition =
    compositionOf {
      clip(MediaSource.of(source)) {
        trim(TRIM_START, TRIM_END)
        snapWithin(SNAP_WITHIN)
      }
    }

  private suspend fun trimmed(): ResolvedComposition = resolved(snapping(fixture(LONG)))

  /**
   * The graph an export of [composition] runs, negotiated through the engine the export itself goes
   * through.
   */
  private suspend fun resolved(composition: EditComposition): ResolvedComposition =
    withContext(Dispatchers.Default) {
      when (val result = engine().resolve(composition, ExportSpec())) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private suspend fun plan(composition: EditComposition): ExportPlan =
    withContext(Dispatchers.Default) {
      when (val verdict = filmstrip.plan(composition, ExportSpec())) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("$verdict")
      }
    }

  private fun engine() =
    ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build().let { components ->
      avFoundationExportEngine(components)
    }

  private fun driver() =
    AvDriver(chainedProber(ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()))

  private fun sourceTrack(
    path: String,
    mediaType: String?,
  ): AVAssetTrack =
    withClue("$path has no $mediaType track") {
      (
        AVURLAsset(
          uRL = NSURL.fileURLWithPath(path),
          options = null,
        ).tracksWithMediaType(mediaType).firstOrNull() as? AVAssetTrack
      ).shouldNotBeNull()
    }

  private companion object {
    const val LONG = "apple_export_long.mp4"

    val TIMEOUT = 2.seconds * 60

    // Inside the twelve second fixture and between two of its sync samples, which sit a second
    // apart, so the snap has somewhere to move to and the clip has room either side of it.
    val TRIM_START = 3_500.milliseconds
    val TRIM_END = 7.seconds

    // How far the cut may move. The fixture's sync samples sit a second apart, so this reaches the
    // one before TRIM_START.
    val SNAP_WITHIN = 2.seconds

    // A frame of the fixture, which is what the written duration can honestly differ by.
    val FRAME = 34.milliseconds
  }
}

/**
 * Where the first segment of this asset's [mediaType] track was cut from the source.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVAsset.firstCut(mediaType: String?): SourceCut? {
  val track = tracksWithMediaType(mediaType).firstOrNull() as? AVAssetTrack ?: return null
  val segment = track.segments.firstOrNull() as? AVAssetTrackSegment ?: return null

  return segment.timeMapping.useContents {
    SourceCut(source.start.readValue().toDuration(), source.duration.readValue().toDuration())
  }
}

// The source each segment reads, with a segment that carries on where the last one ended joined to it, since a held
// frame is its own segment.
@OptIn(ExperimentalForeignApi::class)
private fun AVAsset.joinedCuts(mediaType: String?): List<SourceCut> {
  val track = tracksWithMediaType(mediaType).firstOrNull() as? AVAssetTrack ?: return emptyList()
  return track.segments
    .filterIsInstance<AVAssetTrackSegment>()
    .filterNot { it.empty }
    .map { segment ->
      segment.timeMapping.useContents {
        SourceCut(source.start.readValue().toDuration(), source.duration.readValue().toDuration())
      }
    }.fold(emptyList()) { joined, cut ->
      val last = joined.lastOrNull()
      if (last != null && (cut.start - last.end).absoluteValue <= MEDIA_TICK) {
        joined.dropLast(1) + SourceCut(last.start, cut.end - last.start)
      } else {
        joined + cut
      }
    }
}

private class SourceCut(
  val start: Duration,
  duration: Duration,
) {
  val end: Duration = start + duration
}
