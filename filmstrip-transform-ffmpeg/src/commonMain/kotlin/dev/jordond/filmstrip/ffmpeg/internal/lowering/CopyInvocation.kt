package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.videoSlots
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * What a source with no file says when it reaches a graph.
 */
internal const val UNREADABLE_SOURCE: String =
  "A source with no file reached the graph. FfmpegPlanner refuses those before it lowers."

/**
 * The invocation of a plan on the copy path, which reads the first clip's source across whole or windowed and runs no
 * graph at all.
 *
 * A window that ends before the source does is bounded twice. The input bound ends the audio, and [copyVideoPackets],
 * the count [dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf] read off the clip's video packets, is where
 * the video stops. The engine reads the packets before it plans such a copy and refuses one it could not read them for.
 */
internal fun ResolvedComposition.copyInvocation(
  seekBase: Duration?,
  sdColors: Map<MediaSource, SdColor>,
  bytesRoot: BytesRoot,
  copyVideoPackets: Int?,
): Invocation {
  val clip = tracks.first().clips.first()
  val windowsEnd = clip.end < clip.info.duration
  val source =
    InputSource.OfPath(
      checkNotNull(readablePath(clip.source, bytesRoot)) { UNREADABLE_SOURCE },
      clip.source.describe(),
    )
  return Invocation(
    inputs =
      listOf(
        InputSpec(
          source = source,
          // Bounds only where the clip actually windows the source. A clip that runs to the
          // source's own end reads to it rather than asking for a length, and an untrimmed one
          // copies the container across whole, which is what leaves its bytes untouched.
          durationSeconds =
            clip.sourceDuration.toDouble(DurationUnit.SECONDS).takeIf { windowsEnd },
          startSeconds = clip.start.takeIf { it > Duration.ZERO }?.toDouble(DurationUnit.SECONDS),
        ),
      ),
    filterGraph = "",
    videoLabel = null,
    audioLabel = null,
    output = output,
    videoEncoder = null,
    duration = duration,
    copy = true,
    previewColor = sdColors[clip.source]?.toBt709Nodes(clip.decodedFrame).orEmpty(),
    hdrTransfer = hdrTransfer,
    seekBase = seekBase,
    seekSpeed = clip.speed,
    videoSlots = videoSlots,
    copyVideoPackets = copyVideoPackets?.takeIf { windowsEnd },
  )
}
