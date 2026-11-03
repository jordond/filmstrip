package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlin.time.Duration

/**
 * Writes the audio half of the graph [GraphLowering] builds, onto the same [graph] and [inputs].
 *
 * Every track carrying sound is joined clip by clip, delayed to its own start, then mixed and cut to
 * the composition's duration. A clip whose source has no audio contributes silence of its own length.
 * [branchPad] hands out the pad a laid clip reads its audio from, by track and by clip.
 */
internal class AudioLowering(
  negotiated: ResolvedComposition,
  private val graph: FilterGraphBuilder,
  private val inputs: MutableList<InputSpec>,
  private val branchPad: (trackIndex: Int, clipIndex: Int) -> String,
) {
  private val tracks = negotiated.tracks
  private val format = checkNotNull(negotiated.output.audioFormat) { "AudioLowering needs an audio format" }
  private val duration = negotiated.duration

  fun build(): String {
    val contributing =
      tracks.withIndex().filter { (_, track) ->
        track.content != TrackContent.Video && track.clips.isNotEmpty()
      }
    if (contributing.isEmpty()) return silence()

    val trackLabels = contributing.map { (trackIndex, track) -> trackLabel(trackIndex, track) }
    val mixed =
      if (trackLabels.size == 1) {
        trackLabels.single()
      } else {
        mix(trackLabels, contributing.maxOf { (_, track) -> track.duration })
      }
    return finish(mixed)
  }

  // One track's clips joined in order and delayed to the track's own start.
  private fun trackLabel(
    trackIndex: Int,
    track: ResolvedTrack,
  ): String {
    val segments =
      track.clips.mapIndexed { clipIndex, clip ->
        val label = "a${trackIndex}c$clipIndex"
        graph.chain(
          listOf(audioPad(clip, trackIndex, clipIndex)),
          audioNodes(clip),
          label,
        )
        label
      }

    var label =
      if (segments.size == 1) {
        segments.single()
      } else {
        "at$trackIndex".also {
          graph.chain(
            segments,
            listOf(FilterNode("concat", "n" to segments.size.toString(), "v" to "0", "a" to "1")),
            it,
          )
        }
      }

    if (track.start > Duration.ZERO) {
      val delayed = "ad$trackIndex"
      graph.chain(
        listOf(label),
        listOf(FilterNode("adelay", "delays" to track.start.inWholeMilliseconds.toString(), "all" to "1")),
        delayed,
      )
      label = delayed
    }
    return label
  }

  // Every track mixed into one behind a silent lead as long as the longest of them.
  private fun mix(
    trackLabels: List<String>,
    longest: Duration,
  ): String {
    // amix's limit, not the mix's: below ffmpeg 7 it stops reading its first input the moment
    // that input ends and throws away whatever of it has not been mixed yet. On ffmpeg 6 a
    // looping track hands its later passes over in one burst just before it ends, so as the
    // first input it lost every pass after the first. Silence as long as the longest track takes
    // that place instead, and it only ends once amix asks for more of it, when none is waiting.
    val lead = "alead"
    graph.chain(
      emptyList(),
      listOf(
        FilterNode("anullsrc", "r" to format.sampleRate.toString(), "cl" to "stereo"),
        atrimTo(longest),
        aformat(),
      ),
      lead,
    )
    return "amixed".also { label ->
      graph.chain(
        listOf(lead) + trackLabels,
        // normalize defaults to true and divides the output by the input count, which would
        // silently halve the dialogue the moment a music bed is added. dropout_transition
        // defaults to a two-second ramp when an input ends, which would fade the primary track
        // up when the bed runs out.
        listOf(
          FilterNode(
            "amix",
            "inputs" to (trackLabels.size + 1).toString(),
            "duration" to "longest",
            "dropout_transition" to "0",
            "normalize" to "0",
          ),
        ),
        label,
      )
    }
  }

  // The mix cut to the composition's duration, with its timestamps restarted and its format pinned.
  private fun finish(mixed: String): String {
    val out = "aout"
    graph.chain(
      listOf(mixed),
      listOf(
        atrimTo(duration),
        ASETPTS,
        FilterNode("aresample", "async" to "1", "first_pts" to "0"),
        aformat(),
      ),
      out,
    )
    return out
  }

  // A clip whose source carries no audio still occupies time on the track, so it contributes
  // silence for exactly its own length. Without that a concat drifts by the silent clip. The filler
  // is per pass rather than split off one input, since a pass the run cut short holds less time
  // than the ones before it.
  private fun audioPad(
    clip: ResolvedClip,
    trackIndex: Int,
    clipIndex: Int,
  ): String {
    if (clip.hasAudio) return branchPad(trackIndex, clipIndex)
    val generated = inputs.size
    inputs +=
      InputSpec(
        source = InputSource.Generated("anullsrc=r=${format.sampleRate}:cl=stereo"),
        durationSeconds = clip.duration.seconds(),
      )
    return "$generated:a"
  }

  private fun audioNodes(clip: ResolvedClip): List<FilterNode> =
    buildList {
      if (clip.hasAudio) {
        add(
          FilterNode(
            "atrim",
            "start" to formatSeconds(clip.start.seconds()),
            "end" to formatSeconds(clip.end.seconds()),
          ),
        )
      }
      add(ASETPTS)
      add(aformat())
      // Every scope's level is already multiplied into this, the composition's included, so the mix
      // carries no gain node of its own.
      val constant = clip.gain.constant
      if (constant != null) {
        if (constant != 1f) add(FilterNode("volume", "volume" to constant.toString()))
      } else {
        // volume's limit, not the curve's: it reads its expression once per frame, so the frame
        // size is what the ramp steps at. A default 1024-sample frame steps a fade every 21 ms,
        // which zippers on a tone, so the frames are cut to RAMP_FRAME_SAMPLES ahead of it. Padding
        // is off because asetnsamples otherwise rounds the last frame up with silence, and the
        // concat below would lay that between this clip and the next.
        add(FilterNode("asetnsamples", "n" to RAMP_FRAME_SAMPLES.toString(), "p" to "0"))
        volumeExpressions(clip.gain).forEach { add(FilterNode("volume", "volume" to it, "eval" to "frame")) }
      }
    }

  private fun silence(): String {
    val index = inputs.size
    inputs +=
      InputSpec(
        source = InputSource.Generated("anullsrc=r=${format.sampleRate}:cl=stereo"),
        durationSeconds = duration.seconds(),
      )
    return "aout".also { graph.chain(listOf("$index:a"), listOf(ASETPTS, aformat()), it) }
  }

  private fun atrimTo(end: Duration): FilterNode = FilterNode("atrim", "end" to formatSeconds(end.seconds()))

  private fun aformat(): FilterNode =
    FilterNode(
      "aformat",
      listOf(
        FilterArgument("sample_fmts", "fltp"),
        FilterArgument("sample_rates", format.sampleRate.toString()),
        FilterArgument("channel_layouts", if (format.channelCount == 1) "mono" else "stereo"),
      ),
    )

  private companion object {
    val ASETPTS = FilterNode("asetpts", "expr" to "PTS-STARTPTS")
  }
}

private val ResolvedClip.hasAudio: Boolean get() = info.audio != null

// The frame size a ramping branch is cut to, a literal because it pins ffmpeg's own granularity
// rather than anything the shared curve says. 64 samples is 1.3 ms at 48 kHz, against the 21 ms a
// default 1024-sample frame would step a fade at.
private const val RAMP_FRAME_SAMPLES = 64
