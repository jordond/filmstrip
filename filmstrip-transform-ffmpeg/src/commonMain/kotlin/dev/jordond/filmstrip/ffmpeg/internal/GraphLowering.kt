package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.transform.internal.NegotiatedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.paintsFillAfterEffects
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Writes the graph.
 *
 * One input per clip in declaration order, then one per overlay image, then one per silence filler.
 * Every clip is normalized to the output frame before it is joined, because `concat` demands
 * uniform inputs, and every audio branch is normalized to one sample format for the same reason.
 *
 * @param hdrPixelFormat The format a kept grade is written in, from [hdrPixelFormatFor], or null
 *   for an output that carries none.
 */
internal class GraphLowering(
  private val negotiated: NegotiatedComposition,
  private val toneMapRoute: ToneMapRoute?,
  hdrPixelFormat: String?,
) {
  private val tracks = negotiated.tracks
  private val output = negotiated.output
  private val duration = negotiated.duration

  // The negotiator always resolves one. Null only survives an OutputFormat a caller built by hand,
  // which a NegotiatedComposition never carries.
  private val frameRate = checkNotNull(output.frameRate) { "The negotiated output has no frame rate." }

  private val inputs = mutableListOf<InputSpec>()
  private val clipInput = mutableListOf<MutableList<Int>>()
  private val graph = FilterGraphBuilder()

  // Every file the graph's nodes reach for by placeholder, gathered as the fragments carrying them
  // are consumed.
  private val sidecars = mutableListOf<Sidecar>()

  // The pixel format every clip's tail is pinned to, so concat sees uniform inputs. Falls back to
  // yuv420p whenever there is no grade to carry or the resolved encoder has no HDR profile.
  private val pixelFormat: String = hdrPixelFormat ?: "yuv420p"

  // The source time composition time zero maps to, for a caller windowing this graph with an input
  // seek. Only a single clip on a track that opens with the composition can be windowed: a concat's
  // later branches and a tpad lead both count from a timeline the seek has already moved, so a
  // graph carrying either has to be read forward from the start instead.
  private val seekBase: Duration? =
    tracks
      .firstOrNull()
      ?.takeIf { it.start == Duration.ZERO && it.clips.size == 1 }
      ?.clips
      ?.first()
      ?.start

  // Held back only when a composition effect could actually see the fill: with none present the
  // graph this writes is pixel-identical to the undeferred one, so keeping that simpler graph then
  // is a cost optimisation, not a divergence.
  private val deferFill: Boolean =
    negotiated.paintsFillAfterEffects && negotiated.compositionEffects.isNotEmpty()

  // The format a clip's tail actually ends on. Alpha is what marks a pixel the fill has not painted
  // yet, so every clip carries it through concat and the composition effects while the fill is
  // deferred. A composition filter that cannot carry an alpha channel would flatten that marker to
  // opaque before the final overlay ever sees it, but every filter this backend's own effect chain
  // lowers to can.
  private val tailPixelFormat: String = if (deferFill) alphaPixelFormat(pixelFormat) else pixelFormat

  // The colour a pad or tpad frame renders where there is nothing there yet: transparent while the
  // fill is deferred, so the flatten at the end of the graph can tell an empty pixel from one it
  // painted itself. The fill's own colour otherwise, since nothing runs after it to need telling
  // apart.
  private val padColor: String = if (deferFill) transparentColor() else fillColor(negotiated.fill)

  fun build(): Invocation {
    if (negotiated.path == ExportPath.Transmux) {
      val clip = tracks.first().clips.first()
      val source = InputSource.OfPath(checkNotNull(readablePath(clip.source)) { UNREADABLE_SOURCE })
      return Invocation(
        inputs = listOf(InputSpec(source)),
        filterGraph = "",
        videoLabel = null,
        audioLabel = null,
        output = output,
        videoEncoder = null,
        duration = duration,
        copy = true,
        hdrTransfer = negotiated.hdrTransfer,
        seekBase = seekBase,
      )
    }

    // The planner asks the negotiator to fan composition geometry onto each clip, because concat
    // leaves no composited frame to run it on. Nothing here reads the composited list, so an engine
    // that stopped asking would drop those effects rather than misplace them.
    check(negotiated.compositionGeometry.isEmpty()) {
      "Composition geometry reached the graph, which lowers it per clip and has nowhere to put it."
    }

    tracks.forEach { track ->
      val indices = mutableListOf<Int>()
      track.clips.forEach { clip ->
        indices += inputs.size
        inputs +=
          InputSpec(
            source = InputSource.OfPath(checkNotNull(readablePath(clip.source)) { UNREADABLE_SOURCE }),
            loop = track.looping,
          )
      }
      clipInput += indices
    }

    val videoLabel = if (negotiated.audio == AudioSpec.AudioOnly) null else buildVideo()
    val audioLabel = if (output.audioFormat == null) null else buildAudio()

    return Invocation(
      inputs = inputs,
      filterGraph = graph.build(*listOfNotNull(videoLabel, audioLabel).toTypedArray()),
      videoLabel = videoLabel,
      audioLabel = audioLabel,
      output = output,
      videoEncoder = negotiated.encoderName,
      duration = duration,
      hdrTransfer = negotiated.hdrTransfer,
      toneMapped = negotiated.hdr == ResolvedHdr.ToneMap,
      seekBase = seekBase,
      // Two clips carrying the same grade lower to the same file, and a placeholder names its
      // contents, so the one path serves both references.
      sidecars = sidecars.distinct(),
    )
  }

  private fun buildVideo(): String {
    val primary = tracks.first()
    val segments = primary.clips.mapIndexed { index, clip -> buildClip(clip, clipInput[0][index]) }

    var current =
      if (segments.size == 1) {
        segments.single()
      } else {
        "vcat".also { label ->
          graph.chain(
            segments,
            listOf(FilterNode("concat", "n" to segments.size.toString(), "v" to "1", "a" to "0")),
            label,
          )
        }
      }

    if (primary.start > Duration.ZERO) {
      val padded = "vlead"
      graph.chain(
        listOf(current),
        listOf(
          FilterNode(
            "tpad",
            "start_duration" to formatSeconds(primary.start.seconds()),
            // A gap has no frame of its own to blur, so it falls back to the same pad colour
            // every other empty region uses: the fill's own, or transparent while that colour is
            // still waiting for composition effects to run.
            "color" to padColor,
          ),
        ),
        padded,
      )
      current = padded
    }

    // Composition Colour, Spatial and Composite effects run once, after the join. Only Geometry has
    // to be fanned onto each clip, and the negotiator already did that.
    negotiated.compositionEffects.forEachIndexed { index, resolved ->
      val fragment = resolved.effect.fragment
      sidecars += fragment.sidecars
      val label = "vfx$index"
      val merge = fragment.merge
      if (merge == null) {
        graph.chain(listOf(current), fragment.chain.ifEmpty { listOf(NULL_VIDEO) }, label)
      } else {
        graph.chain(listOf(current) + auxPads(fragment.auxInputs, label), fragment.chain + merge, label)
      }
      current = label
    }

    // The effect chain is where a frame can go through RGB and come back, and what comes back is
    // untagged. ffmpeg 7 and newer reads the grade off the frames either side of it, but below that
    // the conversion out of RGB falls back to BT.601 and the picture is written through the wrong
    // matrix.
    gradeNodes().takeIf { it.isNotEmpty() && negotiated.compositionEffects.isNotEmpty() }?.let { nodes ->
      val graded = "vgrade"
      graph.chain(listOf(current), nodes, graded)
      current = graded
    }

    val out = "vout"
    if (deferFill) {
      val background = "vbg"
      graph.chain(
        emptyList(),
        listOf(
          FilterNode(
            "color",
            "c" to fillColor(negotiated.fill),
            "s" to "${output.size.width}x${output.size.height}",
            "r" to frameRate.toString(),
          ),
        ) + gradeNodes(),
        background,
      )
      graph.chain(
        listOf(background, current),
        listOf(
          overlayNode("shortest" to "1"),
          FilterNode("format", "pix_fmts" to pixelFormat),
          trimTo(duration),
          SETPTS,
        ),
        out,
      )
    } else {
      // A terminal chain, so the mapped pad has a name of its own and the composition's duration is
      // enforced in the graph rather than trusted from the inputs.
      graph.chain(listOf(current), listOf(trimTo(duration), SETPTS), out)
    }
    return out
  }

  /**
   * Restates the grade the frames carry, for a stage that drops it.
   *
   * `color` names no colour attributes of its own, and a frame that has been through RGB carries
   * none either. ffmpeg 7 and newer take them from the neighbouring input, so restating costs
   * nothing there, but below that the frame comes out untagged and every conversion after it reads
   * a BT.2020 picture through the wrong matrix.
   */
  private fun gradeNodes(): List<FilterNode> {
    val transfer = negotiated.hdrTransfer ?: return emptyList()

    return listOf(
      FilterNode(
        "setparams",
        "color_primaries" to HDR_PRIMARIES,
        "color_trc" to transfer.ffmpegTag,
        "colorspace" to HDR_MATRIX,
      ),
    )
  }

  /**
   * One clip's whole video branch, from its input pad to the pad the concat reads.
   *
   * Effects accumulate into a single chain until one of them brings inputs of its own. An overlay
   * cannot be spelled inline the way a crop can, so the nodes waiting at that point are flushed onto
   * a pad the merge reads alongside the images it composites.
   */
  private fun buildClip(
    clip: ResolvedClip,
    input: Int,
  ): String {
    var pad = "$input:v"
    var merged = 0
    val pending =
      mutableListOf(
        FilterNode(
          "trim",
          "start" to formatSeconds(clip.start.seconds()),
          "end" to formatSeconds(clip.end.seconds()),
        ),
        SETPTS,
      )

    // Ahead of every effect, and only on the clips that carry a grade. A composition can mix an HDR
    // clip with an SDR one, and running the curve over the SDR clip would change a frame that was
    // already right.
    if (negotiated.hdr == ResolvedHdr.ToneMap && clip.info.video?.hdrTransfer != null) {
      pending += toneMapNodes(checkNotNull(toneMapRoute) { "ResolvedHdr.ToneMap with no tone-map route" })
    }

    clip.effects.forEach { resolved ->
      val fragment = resolved.effect.fragment
      sidecars += fragment.sidecars
      pending += fragment.chain
      val merge = fragment.merge ?: return@forEach
      val label = "v${input}m$merged"
      graph.chain(listOf(pad) + auxPads(fragment.auxInputs, label), pending + merge, label)
      pending.clear()
      pad = label
      merged++
    }

    val fill = negotiated.fill
    if (negotiated.fit == Fit.Contain && fill is Fill.Blurred) {
      return buildBlurredTail(pad, pending, fill, input)
    }

    pending += tailNodes(output.size, negotiated.fit, padColor, frameRate, tailPixelFormat)
    return "v$input".also { graph.chain(listOf(pad), pending, it) }
  }

  /**
   * The blurred-contain tail: a sharp, contained copy of the frame laid over a blurred copy that
   * covers the whole output.
   *
   * `tailNodes` writes a single chain, but this needs the frame twice, once for each copy, so
   * [pending] is flushed onto a pad of its own and fanned out with [FilterGraphBuilder.split]
   * instead.
   */
  private fun buildBlurredTail(
    pad: String,
    pending: List<FilterNode>,
    fill: Fill.Blurred,
    input: Int,
  ): String {
    val pre = "v${input}pre"
    graph.chain(listOf(pad), pending, pre)

    val background = "v${input}bg"
    val foreground = "v${input}fg"
    graph.split(pre, listOf(background, foreground))

    val blurred = "v${input}blur"
    graph.chain(listOf(background), coverBlurNodes(output.size, fill), blurred)

    val sharp = "v${input}sharp"
    graph.chain(listOf(foreground), containNodes(output.size), sharp)

    return "v$input".also {
      graph.chain(listOf(blurred, sharp), overlayNodes() + trailingNodes(frameRate, tailPixelFormat), it)
    }
  }

  /**
   * The extra inputs a merging effect composites, each prepared on a pad of its own.
   */
  private fun auxPads(
    auxInputs: List<AuxInput>,
    label: String,
  ): List<String> =
    auxInputs.mapIndexed { index, aux ->
      val input = inputs.size
      inputs += InputSpec(source = InputSource.OfImage(aux.image))
      "${label}a$index".also { graph.chain(listOf("$input:v"), aux.chain.ifEmpty { listOf(NULL_VIDEO) }, it) }
    }

  private fun buildAudio(): String {
    val format = output.audioFormat ?: error("buildAudio needs an audio format")
    val contributing =
      tracks.withIndex().filter { (_, track) ->
        track.content != TrackContent.Video && track.clips.isNotEmpty()
      }
    if (contributing.isEmpty()) return silence(format.sampleRate, format.channelCount)

    val trackLabels =
      contributing.map { (trackIndex, track) ->
        val segments =
          track.clips.mapIndexed { clipIndex, clip ->
            val label = "a${clipInput[trackIndex][clipIndex]}"
            graph.chain(
              listOf(audioPad(clip, clipInput[trackIndex][clipIndex], format.sampleRate)),
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
        label
      }

    val mixed =
      if (trackLabels.size == 1) {
        trackLabels.single()
      } else {
        "amixed".also { label ->
          graph.chain(
            trackLabels,
            // normalize defaults to true and divides the output by the input count, which would
            // silently halve the dialogue the moment a music bed is added. dropout_transition
            // defaults to a two-second ramp when an input ends, which would fade the primary track
            // up when the bed runs out.
            listOf(
              FilterNode(
                "amix",
                "inputs" to trackLabels.size.toString(),
                "duration" to "longest",
                "dropout_transition" to "0",
                "normalize" to "0",
              ),
            ),
            label,
          )
        }
      }

    val out = "aout"
    graph.chain(
      listOf(mixed),
      listOf(
        atrimTo(duration),
        ASETPTS,
        FilterNode("aresample", "async" to "1", "first_pts" to "0"),
        aformat(format.sampleRate, format.channelCount),
      ),
      out,
    )
    return out
  }

  // A clip whose source carries no audio still occupies time on the track, so it contributes
  // silence for exactly its own length. Without that a concat drifts by the silent clip.
  private fun audioPad(
    clip: ResolvedClip,
    inputIndex: Int,
    sampleRate: Int,
  ): String {
    if (clip.hasAudio) return "$inputIndex:a"
    val generated = inputs.size
    inputs +=
      InputSpec(
        source = InputSource.Generated("anullsrc=r=$sampleRate:cl=stereo"),
        durationSeconds = clip.duration.seconds(),
      )
    return "$generated:a"
  }

  private fun audioNodes(clip: ResolvedClip): List<FilterNode> =
    buildList {
      val format = output.audioFormat ?: error("audioNodes needs an audio format")
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
      add(aformat(format.sampleRate, format.channelCount))
      // Every scope's level is already multiplied into this, the composition's included, so the mix
      // carries no gain node of its own.
      if (clip.gain != 1f) add(FilterNode("volume", "volume" to clip.gain.toString()))
    }

  private fun silence(
    sampleRate: Int,
    channels: Int,
  ): String {
    val index = inputs.size
    inputs +=
      InputSpec(
        source = InputSource.Generated("anullsrc=r=$sampleRate:cl=stereo"),
        durationSeconds = duration.seconds(),
      )
    return "aout".also { graph.chain(listOf("$index:a"), listOf(ASETPTS, aformat(sampleRate, channels)), it) }
  }

  private fun trimTo(end: Duration): FilterNode = FilterNode("trim", "end" to formatSeconds(end.seconds()))

  private fun atrimTo(end: Duration): FilterNode = FilterNode("atrim", "end" to formatSeconds(end.seconds()))

  private fun aformat(
    sampleRate: Int,
    channels: Int,
  ): FilterNode =
    FilterNode(
      "aformat",
      listOf(
        FilterArgument("sample_fmts", "fltp"),
        FilterArgument("sample_rates", sampleRate.toString()),
        FilterArgument("channel_layouts", if (channels == 1) "mono" else "stereo"),
      ),
    )

  private companion object {
    const val UNREADABLE_SOURCE =
      "A source with no file reached the graph. FfmpegPlanner refuses those before it lowers."

    val SETPTS = FilterNode("setpts", "expr" to "PTS-STARTPTS")
    val ASETPTS = FilterNode("asetpts", "expr" to "PTS-STARTPTS")
    val NULL_VIDEO = FilterNode("null")
  }
}

private val ResolvedClip.hasAudio: Boolean get() = info.audio != null

private fun Duration.seconds(): Double = toDouble(DurationUnit.SECONDS)
