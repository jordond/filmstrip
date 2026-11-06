package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.DEFAULT_BYTES_ROOT
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.geometry.showsBlurredFill
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.effectsOverFill
import dev.jordond.filmstrip.transform.internal.resolve.effectsUnderFill
import dev.jordond.filmstrip.transform.internal.resolve.paintsFillAfterEffects
import dev.jordond.filmstrip.transform.internal.videoSlots
import kotlin.time.Duration

/**
 * Writes the graph.
 *
 * One input per clip in declaration order, then one per overlay image, then one per silence filler.
 * Every clip is normalized to the output frame before it is joined, because `concat` demands
 * uniform inputs, and every audio branch is normalized to one sample format for the same reason.
 * Each clip's picture is cut to the output slots its span covers, so every clip opens on the slot
 * its own start lands on. Its sound is cut at its own exact times. Where an audio track runs past the
 * primary, the primary's last finished frame is held to the composition's end.
 *
 * @param hdrPixelFormat The format a kept grade is written in, from [hdrPixelFormatFor], or null
 *   for an output that carries none.
 * @param sdColors The sources on SD primaries, and the colour each decodes with.
 * @param keepsSdColor Whether a plan of one SD colour can keep it, so no effect in it reads the frame's colour.
 */
internal class GraphLowering(
  private val negotiated: ResolvedComposition,
  hdrPixelFormat: String?,
  private val bytesRoot: BytesRoot = DEFAULT_BYTES_ROOT,
  private val sdColors: Map<MediaSource, SdColor> = emptyMap(),
  private val keepsSdColor: Boolean = true,
) {
  private val tracks = negotiated.tracks
  private val output = negotiated.output
  private val duration = negotiated.duration
  private val frameRate = output.frameRate

  private val inputs = mutableListOf<InputSpec>()

  // The input each track's clips are opened through, by track and then by
  // ResolvedClip.sourceIndex, so every pass of one clip reads the same decoder.
  private val clipInput = mutableListOf<Map<Int, Int>>()
  private val graph = FilterGraphBuilder()

  // The pads waiting to be handed out for an input whose branch was split, keyed by the pad the
  // split reads. Filled the first time a pass asks for a branch of a clip the plan laid more than
  // once, and drained in the order the passes are lowered.
  private val branches = mutableMapOf<String, ArrayDeque<String>>()

  // Every file the graph's nodes reach for by placeholder, gathered as the fragments carrying them
  // are consumed.
  private val sidecars = mutableListOf<Sidecar>()

  // The pixel format every clip's tail is pinned to, so concat sees uniform inputs. Falls back to
  // yuv420p whenever there is no grade to carry or the resolved encoder has no HDR profile.
  private val pixelFormat: String = hdrPixelFormat ?: "yuv420p"

  // What each laid clip decodes with. A plan of one SD colour keeps it, codes and tags, unless an effect left in it
  // draws on the frame's colour, which it reads as BT.709 on every backend. Anything else ends up on one tag for every
  // clip, and BT.709 is the one both a tone map and a clip on no SD colour already read as.
  private val laidColors: List<SdColor?> by lazy {
    tracks
      .firstOrNull()
      ?.clips
      .orEmpty()
      .filter { slotsOf(it) > 0 }
      .map { sdColors[it.source] }
      .distinct()
  }
  private val keptSdColor: SdColor? by lazy {
    laidColors.singleOrNull().takeIf { keepsSdColor && negotiated.hdr != ResolvedHdr.ToneMap }
  }
  private val writesBt709: Boolean by lazy {
    negotiated.hdr == ResolvedHdr.ToneMap || (keptSdColor == null && laidColors.any { it != null })
  }

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

  // The composition effects either side of a color fill. Overlays run over it, so they draw on the
  // bars and gaps as well as the picture. Everything else runs under it and never reaches it.
  private val effectsUnderFill = negotiated.effectsUnderFill
  private val effectsOverFill = negotiated.effectsOverFill

  // Held back when an effect under the fill could actually see it, or a clip effect after a canvas could grade that
  // canvas's bars, or on a kept grade, where the fill has to land on codes a pad color cannot name and only the
  // flatten writes them. Otherwise the graph this writes is pixel-identical to the undeferred one, so keeping that
  // simpler graph then is a cost optimisation, not a divergence. An SDR composition carrying only overlays paints the
  // fill straight in and never goes through an alpha format.
  private val deferFill: Boolean =
    negotiated.paintsFillAfterEffects &&
      (effectsUnderFill.isNotEmpty() || negotiated.hdrTransfer != null || negotiated.buildsCanvas)

  // The format a clip's tail actually ends on. Alpha is what marks a pixel the fill has not painted
  // yet, so every clip carries it through concat and the effects under the fill while the fill is
  // deferred. A composition filter that cannot carry an alpha channel would flatten that marker to
  // opaque before the flatten ever sees it, but every filter this backend's own effect chain lowers
  // to can.
  private val tailPixelFormat: String = if (deferFill) alphaPixelFormat(pixelFormat) else pixelFormat

  // The color a pad or tpad frame renders where there is nothing there yet: transparent while the
  // fill is deferred, so the flatten can tell an empty pixel from one it painted itself. The fill's
  // own color otherwise, since nothing under the fill runs after it to need telling apart.
  private val padColor: String = if (deferFill) transparentColor() else fillColor(negotiated.fill)

  fun build(): Invocation {
    if (negotiated.path == ExportPath.Copy) return negotiated.copyInvocation(seekBase, sdColors, bytesRoot)

    // One input per clip the caller wrote, not per pass the plan laid. A looping track names the
    // same clip once for every pass, and a second decoder on the same file would decode it again.
    // The split those passes read from is not free either: it clones every frame onto all of its
    // outputs out of one buffer, and concat pulls from the branch it is on and no other, so the
    // frames every branch behind it has been handed sit in the graph until concat reaches them.
    // That is up to one pass of decoded frames held per branch still waiting.
    tracks.forEach { track ->
      val indices = mutableMapOf<Int, Int>()
      track.clips.forEach { clip ->
        indices.getOrPut(clip.sourceIndex) {
          val path = checkNotNull(readablePath(clip.source, bytesRoot)) { UNREADABLE_SOURCE }
          inputs.size.also { inputs += InputSpec(source = InputSource.OfPath(path, clip.source.describe())) }
        }
      }
      clipInput += indices
    }

    val videoLabel = if (negotiated.audio == AudioSpec.AudioOnly) null else buildVideo()
    val audioLabel =
      if (output.audioFormat == null) {
        null
      } else {
        AudioLowering(negotiated, graph, inputs) { track, clip -> branchPad(track, clip, audio = true) }.build()
      }

    return Invocation(
      inputs = inputs,
      filterGraph = graph.build(*listOfNotNull(videoLabel, audioLabel).toTypedArray()),
      videoLabel = videoLabel,
      audioLabel = audioLabel,
      output = output,
      videoEncoder = negotiated.encoderName,
      duration = duration,
      hdrTransfer = negotiated.hdrTransfer,
      writesBt709 = writesBt709,
      previewColor = keptSdColor?.toBt709Nodes(output.size).orEmpty(),
      seekBase = seekBase,
      videoSlots = videoLabel?.let { negotiated.videoSlots },
      heldTail = if (videoLabel == null) LongRange.EMPTY else negotiated.heldTail,
      // Two clips carrying the same grade lower to the same file, and a placeholder names its
      // contents, so the one path serves both references.
      sidecars = sidecars.distinct(),
    )
  }

  /**
   * The pad the [index]th laid clip of [trackIndex] reads its own branch from.
   *
   * A pad may be read only once, so a clip the plan laid more than once cannot have every pass read
   * its input directly. The first pass to ask splits the branch into one pad per pass, and the rest
   * take theirs from what that left.
   */
  private fun branchPad(
    trackIndex: Int,
    index: Int,
    audio: Boolean,
  ): String {
    val clips = tracks[trackIndex].clips
    val sourceIndex = clips[index].sourceIndex
    val input = clipInput[trackIndex].getValue(sourceIndex)
    val pad = if (audio) "$input:a" else "$input:v"
    // A pass that lays no picture reads no video branch, and a split outlet nothing reads is a graph
    // ffmpeg refuses to run.
    val passes = clips.count { it.sourceIndex == sourceIndex && (audio || slotsOf(it) > 0) }
    if (passes == 1) return pad

    return branches
      .getOrPut(pad) {
        val outlets = List(passes) { pass -> "${if (audio) "a" else "v"}${input}p$pass" }
        if (audio) graph.asplit(pad, outlets) else graph.split(pad, outlets)
        ArrayDeque(outlets)
      }.removeFirst()
  }

  private fun buildVideo(): String {
    val primary = tracks.first()
    // A clip covering no output slot lays no picture at all. Its sound is exact, so it still plays.
    val segments =
      primary.clips.mapIndexedNotNull { index, clip ->
        slotsOf(clip).takeIf { it > 0 }?.let { slots -> buildClip(clip, index, branchPad(0, index, false), slots) }
      }
    check(segments.isNotEmpty()) { "The primary covers no output slot, which the planner refuses before it lowers." }

    // concat stamps its frames in microseconds, where a slot is rarely a whole number of them, so every frame after
    // the join, and every slot a lead's tpad steps from there, lands a little early. A flatten onto a fill on the
    // exact grid would then drop the last slot. Every join is restamped onto the output frame's own time base so each
    // frame sits on its slot whatever runs after it.
    var current =
      if (segments.size == 1) {
        segments.single()
      } else {
        "vcat".also { label ->
          val concat = FilterNode("concat", "n" to segments.size.toString(), "v" to "1", "a" to "0")
          graph.chain(segments, listOf(concat, frameGridBase()), label)
        }
      }

    // The gap ahead of a late track is the slots its start covers, so the first clip opens on the slot
    // its start lands on.
    val lead = outputSlotOf(primary.start, frameRate)
    if (lead > 0) {
      val padded = "vlead"
      graph.chain(
        listOf(current),
        listOf(
          FilterNode(
            "tpad",
            "start" to lead.toString(),
            // A gap has no frame of its own to blur, so it falls back to the same pad color
            // every other empty region uses: the fill's own, or transparent while that color is
            // still waiting for composition effects to run.
            "color" to padColor,
          ),
        ),
        padded,
      )
      current = padded
    }

    // Composition Color, Spatial and Composite effects run once, after the join. Only Geometry has
    // to be fanned onto each clip, and the negotiator already did that. The effects under the fill
    // run first, then a deferred fill is painted in, then the overlays draw on the opaque frame.
    val transfer = negotiated.hdrTransfer
    current = compositionEffects(effectsUnderFill, current, firstIndex = 0)
    if (deferFill) {
      val graded = graph.restateGrade(current, "vgrade", transfer)
      current = graph.flattenOntoFill(graded, negotiated, frameRate, pixelFormat)
      current = compositionEffects(effectsOverFill, current, firstIndex = effectsUnderFill.size)
      if (effectsOverFill.isNotEmpty()) current = graph.restateGrade(current, "vgradeover", transfer)
    } else {
      current = compositionEffects(effectsOverFill, current, firstIndex = effectsUnderFill.size)
      if (negotiated.compositionEffects.isNotEmpty()) current = graph.restateGrade(current, "vgrade", transfer)
    }

    // A terminal chain, so the mapped pad has a name of its own and the composition's duration is
    // enforced in the graph rather than trusted from the inputs.
    val out = "vout"
    val tags = if (writesBt709) listOf(bt709Tags()) else emptyList()
    graph.chain(listOf(current), negotiated.heldTailNodes() + listOf(trimTo(duration), SETPTS) + tags, out)
    return out
  }

  /**
   * Runs [effects] over [read] in order and returns the pad the last one wrote.
   *
   * Each effect writes a pad numbered from [firstIndex], so the effects either side of the fill
   * number on from each other and no two share a label.
   */
  private fun compositionEffects(
    effects: List<ResolvedEffect>,
    read: String,
    firstIndex: Int,
  ): String {
    var current = read
    // Every clip's tail ends on the subsampled tail format, so the joined frame does too.
    var grid = ChromaGrid.Subsampled
    effects.forEachIndexed { offset, resolved ->
      val fragment = resolved.effect.fragment
      sidecars += fragment.sidecars
      val label = "vfx${firstIndex + offset}"
      val merge = fragment.merge
      val crop = fragment.crop
      if (fragment.branches.isNotEmpty()) {
        graph.branches(current, fragment, label, Duration.ZERO)
      } else if (crop != null) {
        graph.cropped(current, fragment, crop, label, grid, ::auxPads)
      } else if (merge == null) {
        graph.chain(listOf(current), (fragment.chain + fragment.post).ifEmpty { listOf(NULL_VIDEO) }, label)
      } else {
        // A chain's input labels link to its first filter, so anything this effect contributes
        // ahead of the merge goes onto a pad of its own rather than between the labels and the
        // merge that reads them. An effect contributing nothing reads the pad it was handed, which
        // is every overlay in the catalogue and leaves their graphs as they were.
        val input =
          if (fragment.chain.isEmpty()) {
            current
          } else {
            "${label}pre".also { graph.chain(listOf(current), fragment.chain, it) }
          }
        graph.chain(listOf(input) + auxPads(fragment.auxInputs, label), listOf(merge) + fragment.post, label)
      }
      if (crop == null) grid = grid.after(fragment.branches.flatMap { it.nodes } + fragment.chain + fragment.post)
      current = label
    }
    return current
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
    index: Int,
    branch: String,
    slots: Long,
  ): String {
    var pad = branch
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
    val transfer = clip.info.video?.hdrTransfer
    if (negotiated.hdr == ResolvedHdr.ToneMap && transfer != null) {
      val toneMap = toneMapFragment(transfer)
      sidecars += toneMap.sidecars
      pending += toneMap.chain
    }
    // Ahead of every effect too, so an effect reads the same signal on every clip of a plan written BT.709.
    if (writesBt709) sdColors[clip.source]?.let { pending += it.toBt709Nodes(clip.decodedFrame) }
    // A source decodes to 4:2:0 as far as the graph can tell, so every figure is snapped until a format says otherwise.
    var grid = ChromaGrid.Subsampled.after(pending)

    // Under a blurred fill a canvas's bars stay clear for the clip's effects and turn black once they have run.
    val blackBars = negotiated.fill is Fill.Blurred && clip.placement.canvases.isNotEmpty()

    clip.effects.forEachIndexed { effectIndex, resolved ->
      val fragment = resolved.effect.fragment
      sidecars += fragment.sidecars
      val crop = fragment.crop
      if (crop != null || fragment.branches.isNotEmpty()) {
        val label = "v${index}m$merged"
        val read = if (pending.isEmpty()) pad else "${label}in".also { graph.chain(listOf(pad), pending, it) }
        pending.clear()
        pad =
          if (crop != null) {
            graph.cropped(read, fragment, crop, label, grid, ::auxPads)
          } else {
            grid = grid.after(fragment.branches.flatMap { it.nodes })
            label.also { graph.branches(read, fragment, it, clip.span.start) }
          }
        merged++
        return@forEachIndexed
      }
      pending += fragment.chain
      grid = grid.after(fragment.chain + fragment.post)
      val merge = fragment.merge
      if (merge == null) {
        pending += fragment.post
        // The canvas crop removes no pixels, so its canvas is built here for the effects after it that read it.
        val canvas = clip.canvasEffectIndices.indexOf(effectIndex)
        if (canvas >= 0) {
          val placed = clip.placement.canvases[canvas]
          val built =
            if (blackBars) clearCanvasNodes(placed, pixelFormat) else canvasNodes(placed, padColor, tailPixelFormat)
          pending += built
          grid = grid.after(built)
        }
        return@forEachIndexed
      }
      // A chain's input labels are linked to its first filter, so the nodes waiting here cannot sit
      // between the labels and the merge that reads them: ffmpeg refuses the graph with "More input
      // link labels specified for filter 'trim' than it has inputs". They go onto a pad of their
      // own instead, and the merge reads that pad alongside the images it composites.
      val prepared = "v${index}m${merged}pre"
      graph.chain(listOf(pad), pending.ifEmpty { listOf(NULL_VIDEO) }, prepared)
      pending.clear()

      val label = "v${index}m$merged"
      graph.chain(listOf(prepared) + auxPads(fragment.auxInputs, label), listOf(merge) + fragment.post, label)
      pad = label
      merged++
    }

    if (blackBars) {
      val read = "v${index}clear"
      graph.chain(listOf(pad), pending.ifEmpty { listOf(NULL_VIDEO) }, read)
      pending.clear()
      pad = graph.overBlack(read, "v$index", pixelFormat, grid)
    }

    val rect = clip.drawnIn(output.size)
    val fill = negotiated.fill
    if (fill is Fill.Blurred && clip.placement.showsBlurredFill(output.size)) {
      val tail = TrailingFrames(frameRate, tailPixelFormat, slots)
      return graph.blurredTail(pad, pending, fill, rect, output.size, "v$index", tail, grid)
    }

    pending += tailNodes(output.size, rect, padColor, frameRate, tailPixelFormat, slots, grid)
    return "v$index".also { graph.chain(listOf(pad), pending, it) }
  }

  /**
   * The extra inputs a merging effect composites, each prepared on a pad of its own.
   *
   * An input naming a timeline is looped for a frame longer than the run it covers. Overshooting
   * costs nothing, since the graph's own `trim=duration=` is what cuts the output, while
   * undershooting drops the overlay from the frame the branch runs out with no diagnostic at all.
   *
   * Every branch closes on a `setpts` back to its own first frame. An export reads the input from
   * zero and the node changes nothing, while a preview opens the input at the scrub instead, so
   * anything ahead of the node reads the composition time the frames belong to and the merge below
   * it still sees the clip's own clock. [InputSpec.rebased] is what tells the preview so.
   */
  private fun auxPads(
    auxInputs: List<AuxInput>,
    label: String,
  ): List<String> =
    auxInputs.mapIndexed { index, aux ->
      val input = inputs.size
      inputs +=
        InputSpec(
          source = InputSource.OfImage(aux.image),
          durationSeconds = aux.timeline?.let { it.duration.seconds() + 1.0 / it.frameRate },
          loopFrameRate = aux.timeline?.frameRate,
          rebased = true,
        )
      "${label}a$index".also { graph.chain(listOf("$input:v"), aux.chain + SETPTS, it) }
    }

  // How many output slots a clip's span covers, which is how many frames its picture is cut to.
  private fun slotsOf(clip: ResolvedClip): Long =
    outputSlotsOf(clip.span, frameRate).let { if (it.isEmpty()) 0 else it.last - it.first + 1 }

  private fun trimTo(end: Duration): FilterNode = FilterNode("trim", "end" to formatSeconds(end.seconds()))

  private fun frameGridBase(): FilterNode = FilterNode("settb", "expr" to "1/$frameRate")

  private companion object {
    val SETPTS = FilterNode("setpts", "expr" to "PTS-STARTPTS")
    val NULL_VIDEO = FilterNode("null")
  }
}

// Whether any clip builds a canvas, whose bars stay clear until the fill is painted in so no clip effect grades them.
private val ResolvedComposition.buildsCanvas: Boolean
  get() = tracks.any { track -> track.clips.any { it.placement.readsCanvas } }
