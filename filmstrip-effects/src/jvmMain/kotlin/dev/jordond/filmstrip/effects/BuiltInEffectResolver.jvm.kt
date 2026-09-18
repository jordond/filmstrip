package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.AuxTimeline
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.color.RgbAdjustment
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.isDiagonal
import dev.jordond.filmstrip.effects.color.isIdentity
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayPlacement
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.frameWithin
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.runWithin
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HLG_A
import dev.jordond.filmstrip.media.HLG_B
import dev.jordond.filmstrip.media.HLG_C
import dev.jordond.filmstrip.media.HLG_SCENE_TO_SDR_SIGNAL_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PQ_C1
import dev.jordond.filmstrip.media.PQ_C2
import dev.jordond.filmstrip.media.PQ_C3
import dev.jordond.filmstrip.media.PQ_M1
import dev.jordond.filmstrip.media.PQ_M2
import dev.jordond.filmstrip.media.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.media.SDR_SIGNAL_TO_HLG_SCENE_GAMMA
import dev.jordond.filmstrip.media.sdrSignalCeiling
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Lowers the built-in catalogue onto a filter graph.
 *
 * Every lowering is a pure function of the spec and the resolved [Attributes], so it is assertable
 * in a unit test with no toolchain installed, which neither of the other two resolvers can manage.
 * An animated overlay is the one lowering that reads anything: it needs the overlay picture's own
 * pixel size, which it takes from the image's header rather than by decoding it.
 */
public actual class BuiltInEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.FilterGraph) return null

    return when (spec) {
      is Rotate -> fragment(rotate(spec.degrees))
      is Flip -> fragment(listOf(FilterNode(if (spec.axis == FlipAxis.Horizontal) "hflip" else "vflip")))
      is Crop -> fragment(crop(spec.retainedRect(attributes.inputSize), attributes.inputSize))
      is CropRect -> fragment(crop(spec.rect, attributes.inputSize))
      is KenBurns -> EffectResolution.Unsupported(spec.id, PAN_PENDING)
      // The size stage is the tail the backend pins to the resolved output frame, so the effect
      // that decides that frame contributes no node of its own. Claimed rather than declined,
      // because an unclaimed spec is refused by name at plan time.
      is Scale -> fragment(emptyList())
      is Brightness,
      is RgbAdjustment,
      is Contrast,
      is Saturation,
      is HueRotate,
      is Sepia,
      is Invert,
      is ColorMatrix,
      -> colorMatrix(spec, attributes.hdrTransfer)
      is ImageOverlay -> imageOverlay(spec, attributes)
      is TextOverlay -> EffectResolution.Unsupported(spec.id, textMessage(capabilities))
      else -> null
    }
  }

  private fun fragment(chain: List<FilterNode>): EffectResolution =
    EffectResolution.Resolved(PlatformEffect(FilterFragment(chain = chain)))

  // transpose=dir=cclock turns a frame red-on-the-left into red-on-the-bottom, which is what
  // Rotate documents. Both flips are memory operations, so they are the cheaper half turn.
  private fun rotate(degrees: Int): List<FilterNode> =
    when (((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN) {
      QUARTER_TURN -> listOf(FilterNode("transpose", "dir" to "cclock"))
      HALF_TURN -> listOf(FilterNode("hflip"), FilterNode("vflip"))
      THREE_QUARTER_TURN -> listOf(FilterNode("transpose", "dir" to "clock"))
      else -> emptyList()
    }

  // A graded table runs the transfer function either side of the matrix: decode the code value
  // to the SDR signal its light would have been on a display at reference white, apply the
  // channel's line there, encode it again. A gbrp10le conversion is forced ahead of it because a
  // per-channel nonlinear function has no YUV form, and ten bits is the only depth this backend
  // writes HDR at. Neither zscale nor libplacebo is involved, so it runs on a stock build.
  private fun gradedDiagonalLut(
    matrix: ColorMatrix,
    transfer: HdrTransfer,
  ): List<FilterNode> =
    listOf(
      FilterNode("format", "pix_fmts" to HDR_PLANAR_RGB),
      FilterNode(
        "lutrgb",
        "r" to gradedChannel(matrix.rr, matrix.rBias, transfer, HDR_PLANAR_PEAK),
        "g" to gradedChannel(matrix.gg, matrix.gBias, transfer, HDR_PLANAR_PEAK),
        "b" to gradedChannel(matrix.bb, matrix.bBias, transfer, HDR_PLANAR_PEAK),
      ),
    )

  // The signal the line runs on is scaled so the format's peak reads as one, so the bias is scaled
  // the same way. The floor is the matrix's own, and the ceiling is the format's, in the clip on
  // the way back rather than in the line.
  private fun gradedChannel(
    scale: Float,
    bias: Float,
    transfer: HdrTransfer,
    peak: Int,
  ): String {
    val graded = "max($scale*${scaledSdrSignal(transfer, peak)}+${bias / transfer.sdrSignalCeiling},0)"

    return codeValue(signalFromScaledSdr(graded, transfer), peak)
  }

  // The SDR signal the code value's light decodes to, divided by the transfer's ceiling. PQ's light
  // is a fraction of its peak already. HLG's is scene light, which reaches display light through
  // the per-channel opto-optical transfer first.
  private fun scaledSdrSignal(
    transfer: HdrTransfer,
    peak: Int,
  ): String =
    when (transfer) {
      HdrTransfer.Pq -> "pow(${pqLight(normalized(peak))},${1.0 / SDR_DISPLAY_GAMMA})"
      HdrTransfer.Hlg -> "pow(${hlgScene(normalized(peak))},$HLG_SCENE_TO_SDR_SIGNAL_GAMMA)"
    }

  // The way back from a scaled SDR signal to the transfer's own, in the range zero to one. The clip
  // is where the format runs out.
  private fun signalFromScaledSdr(
    scaled: String,
    transfer: HdrTransfer,
  ): String =
    when (transfer) {
      HdrTransfer.Pq -> pqSignal("clip(pow($scaled,$SDR_DISPLAY_GAMMA),0,1)")
      HdrTransfer.Hlg -> hlgSignal("clip(pow($scaled,$SDR_SIGNAL_TO_HLG_SCENE_GAMMA),0,1)")
    }

  // ST 2084 decoded, as a fraction of its peak.
  private fun pqLight(signal: String): String {
    val encoded = "pow($signal,${1.0 / PQ_M2})"

    return "pow(max($encoded-$PQ_C1,0)/($PQ_C2-$PQ_C3*$encoded),${1.0 / PQ_M1})"
  }

  private fun pqSignal(light: String): String {
    val scaled = "pow($light,$PQ_M1)"

    return "pow(($PQ_C1+$PQ_C2*$scaled)/(1+$PQ_C3*$scaled),$PQ_M2)"
  }

  // Only the inverse OETF, so what comes out is scene light rather than display light.
  private fun hlgScene(signal: String): String =
    "if(lte($signal,0.5),$signal*$signal/3,(exp(($signal-$HLG_C)/$HLG_A)+$HLG_B)/12)"

  private fun hlgSignal(scene: String): String =
    "if(lte($scene,1/12),sqrt(3*$scene),$HLG_A*log(12*$scene-$HLG_B)+$HLG_C)"

  // The graded path forces the pixel format, so the table is scaled by the depth's own top code
  // rather than by lutrgb's maxval. maxval is 255 shifted up to the depth, which reads 1020 on
  // gbrp10le, and scaling by that puts every graded code value about a code low.
  private fun normalized(peak: Int): String = "clip(val/$peak,0,1)"

  // Rounded for the same reason channelExpression rounds: lutrgb keeps the whole part. The clip is
  // lutrgb's own ceiling rather than the format's: the filter refuses to emit above maxval, so on
  // gbrp10le the three codes above 1020 come out as 1020 whatever the table asks for.
  private fun codeValue(
    signal: String,
    peak: Int,
  ): String = "clip(round($peak*($signal)),minval,maxval)"

  // Every colour effect in the catalogue is one affine map of the encoded signal, and it reaches
  // the graph through whichever of two filters spells that map exactly. colorchannelmixer is
  // neither: it fails the graph above a gain of 2 rather than clamping, and it carries no bias.
  //
  // On a kept grade the same map runs on the SDR signal the code value's light decodes to, with
  // the transfer function either side of it, so each of the two filters has a graded form.
  private fun colorMatrix(
    spec: EffectSpec,
    transfer: HdrTransfer?,
  ): EffectResolution {
    val matrix = checkNotNull(colorMatrixOf(spec)) { "${spec.id} has no matrix to lower." }
    return when {
      // Claimed rather than declined, the way Scale is, because an unclaimed spec is refused by
      // name at plan time.
      matrix.isIdentity -> fragment(emptyList())
      !matrix.isDiagonal -> cubeLut(matrix, transfer)
      transfer == null -> fragment(listOf(diagonalLut(matrix)))
      else -> fragment(gradedDiagonalLut(matrix, transfer))
    }
  }

  // Each output channel reads its own input channel alone, so the same lutrgb a brightness lowers
  // to spells the whole matrix. An RGB format's minval is zero, so val carries the signal itself
  // and a bias scaled by maxval pivots a contrast on the middle of the range.
  private fun diagonalLut(matrix: ColorMatrix): FilterNode =
    FilterNode(
      "lutrgb",
      "r" to channelExpression(matrix.rr, matrix.rBias),
      "g" to channelExpression(matrix.gg, matrix.gBias),
      "b" to channelExpression(matrix.bb, matrix.bBias),
    )

  // lutrgb keeps the whole part of what the expression comes to, so the expression rounds first and
  // lands on the code value a shader's output would.
  private fun channelExpression(
    scale: Float,
    bias: Float,
  ): String = "clip(round(val*$scale+$bias*maxval),minval,maxval)"

  // A matrix that mixes channels has no per-channel filter to lower to, so it travels as a
  // two-point table instead. lut3d interpolates an affine map exactly, so eight corners reproduce
  // the matrix rather than sampling it, and it clamps once on the way out, which is where the
  // matrix puts the clamp too. It keeps the code value below the interpolated one rather than the
  // nearest, its own limit, so a mixed matrix here can land one code value under the other backends.
  //
  // On a kept grade the table reads the SDR signal scaled so the format's peak is one, which is
  // the range lut3d interpolates over, so the bias is written against that ceiling too.
  private fun cubeLut(
    matrix: ColorMatrix,
    transfer: HdrTransfer?,
  ): EffectResolution {
    val ceiling = transfer?.sdrSignalCeiling ?: SDR_CEILING
    val sidecar = Sidecar(cube(matrix, ceiling).encodeToByteArray(), "cube")
    val lut = FilterNode("lut3d", "file" to sidecar.placeholder)

    return EffectResolution.Resolved(
      PlatformEffect(
        FilterFragment(
          chain = if (transfer == null) listOf(lut) else betweenTransfers(lut, transfer),
          sidecars = listOf(sidecar),
        ),
      ),
    )
  }

  // lut3d reads a signal in the range zero to one, so on a grade it runs between a table that
  // decodes the code value to the scaled SDR signal and one that encodes it again. Sixteen bits
  // between the two keeps a ten-bit output exact. The two extra format conversions are a cost per
  // frame on a graded export with a mixing matrix, not a change to what it writes: swscale carries
  // rgb48le to gbrp10le and back with no drift, which the planar sixteen-bit format it replaced did
  // not.
  private fun betweenTransfers(
    lut: FilterNode,
    transfer: HdrTransfer,
  ): List<FilterNode> {
    val decode = codeValue(scaledSdrSignal(transfer, WIDE_RGB_PEAK), WIDE_RGB_PEAK)
    val encode = codeValue(signalFromScaledSdr(normalized(WIDE_RGB_PEAK), transfer), WIDE_RGB_PEAK)

    return listOf(
      FilterNode("format", "pix_fmts" to WIDE_RGB),
      FilterNode("lutrgb", "r" to decode, "g" to decode, "b" to decode),
      lut,
      FilterNode("lutrgb", "r" to encode, "g" to encode, "b" to encode),
      FilterNode("format", "pix_fmts" to HDR_PLANAR_RGB),
    )
  }

  // The eight corners of the unit cube, red varying fastest and blue slowest, which is the order a
  // .cube file is read in. Entries are left unclamped so that a corner the matrix pushes past white
  // still lands on the line the interpolation between it and its neighbour needs. The bias is
  // divided by the ceiling of the signal the table reads, which is one for an SDR signal.
  private fun cube(
    matrix: ColorMatrix,
    ceiling: Float,
  ): String =
    buildString {
      appendLine("LUT_3D_SIZE $CUBE_SIZE")
      for (blue in 0..1) {
        for (green in 0..1) {
          for (red in 0..1) {
            val r = matrix.rr * red + matrix.rg * green + matrix.rb * blue + matrix.rBias / ceiling
            val g = matrix.gr * red + matrix.gg * green + matrix.gb * blue + matrix.gBias / ceiling
            val b = matrix.br * red + matrix.bg * green + matrix.bb * blue + matrix.bBias / ceiling
            appendLine("${entry(r)} ${entry(g)} ${entry(b)}")
          }
        }
      }
    }

  // Fixed decimals in the root locale. A comma for the decimal point, or an exponent on a small
  // enough entry, is a file ffmpeg reads as something else.
  private fun entry(value: Float): String = String.format(Locale.ROOT, CUBE_ENTRY_FORMAT, value)

  // Pixels are multiplied out here rather than emitted as an iw/ih expression. An expression that
  // rounds differently from the planner's arithmetic makes plan() and the export disagree about
  // the output frame, which is the one thing plan() exists to prevent.
  private fun crop(
    rect: NormalizedRect,
    inputSize: Size,
  ): List<FilterNode> {
    if (rect == NormalizedRect.Full) return emptyList()

    val width = (inputSize.width * rect.width).toInt().coerceAtLeast(1)
    val height = (inputSize.height * rect.height).toInt().coerceAtLeast(1)
    return listOf(
      FilterNode(
        "crop",
        "w" to width.toString(),
        "h" to height.toString(),
        "x" to (inputSize.width * rect.left).toInt().toString(),
        "y" to (inputSize.height * rect.top).toInt().toString(),
      ),
    )
  }

  // W and H inside overlay are the main frame, w and h the overlay, so the corner arithmetic is
  // written as an expression rather than resolved here. The margin is not: it is a fraction of the
  // output frame's shorter side and the planner already knows both numbers.
  private fun imageOverlay(
    spec: ImageOverlay,
    attributes: Attributes,
  ): EffectResolution {
    val outputSize = attributes.outputSize
    val span = attributes.span
    val run = spec.runWithin(span)
    val margin = (minOf(outputSize.width, outputSize.height) * spec.margin).roundToInt()
    val overlayWidth = (outputSize.width * spec.scale).roundToInt().coerceAtLeast(1)
    val driven = spec.commandsFor(attributes, run)
    if (driven is Driven.Refused) return EffectResolution.Unsupported(spec.id, driven.message)
    val animated = driven as? Driven.Commands
    val instance = animated?.instance.orEmpty()

    val prepare =
      buildList {
        animated?.let { add(FilterNode("sendcmd", "f" to it.sidecar.placeholder)) }
        add(FilterNode("scale$instance", "w" to overlayWidth.toString(), "h" to "-1"))
        add(FilterNode("format", "pix_fmts" to "rgba"))
        // After the format, which is the node that guarantees the alpha channel the gate zeroes.
        spec.windowGate(run, span)?.let { add(it) }
        when {
          // The sampler has already folded the authored opacity into every value it answers, so the
          // node the commands drive opens at one rather than at what was authored.
          animated != null -> add(FilterNode("colorchannelmixer$instance", "aa" to "1"))
          spec.opacity < 1f -> add(FilterNode("colorchannelmixer", "aa" to spec.opacity.toString()))
        }
        // ffmpeg's own limit, and only where a command can resize the branch. Where the frames the
        // overlay merges onto carry alpha, which is what a deferred fill leaves them holding, the
        // converter ffmpeg inserts to bring both inputs to one pixel format is a scale pinned to
        // the size it was configured at, and it silently undoes a commanded resize. A scale of its
        // own input's size, re-read every frame, carries the new size past it.
        animated?.let { add(FilterNode("scale", "w" to "iw", "h" to "ih", "eval" to "frame")) }
      }

    val placement =
      buildList {
        add(FilterArgument("x", if (spec.corner.isTrailing) "W-w-$margin" else "$margin"))
        add(FilterArgument("y", if (spec.corner.isBottom) "H-h-$margin" else "$margin"))
        add(FilterArgument("format", "auto"))
        // What overlay does once the image branch ends: hand the main frame on undrawn. The branch
        // is looped for as long as the one it merges onto, so nothing reaches it.
        add(FilterArgument("eof_action", "pass"))
      }

    return EffectResolution.Resolved(
      PlatformEffect(
        FilterFragment(
          auxInputs = listOf(AuxInput(spec.image, prepare, attributes.auxTimeline())),
          merge = FilterNode("overlay$instance", placement),
          sidecars = listOfNotNull(animated?.sidecar),
        ),
      ),
    )
  }

  // The window, as a node on the overlay's own branch: outside the run the branch is handed on
  // fully transparent, so the merge blends nothing onto the frame under it, and inside it the node
  // is switched off and passes the picture through untouched.
  //
  // t is the image branch's own clock, read ahead of the setpts that rebases it: an export opens
  // that branch at zero and a scrubbed preview carries it to the seek with -itsoffset, so either
  // way it reads the composition time the frame belongs to. It counts from the span's own start,
  // which is what the window is written against.
  //
  // The bound is half open, the way TimeRange and frameWithin read it, so the frame sitting on the
  // run's end is outside it, as it is on every other backend.
  //
  // The branch advances on the output frame grid, so the frames this opens over are the ones the
  // shared sampler names. A clip's merge runs ahead of the tail's fps, and that resampler builds
  // two output frames out of one source frame either side of a boundary, so on a clip whose own
  // rate differs from the output's an edge lands within one output frame of the sampler's. That is
  // the resampler's limit rather than the window's.
  private fun ImageOverlay.windowGate(
    run: TimeRange,
    span: TimeRange,
  ): FilterNode? {
    if (visibleDuring == null) return null
    val end = run.endExclusive ?: return null

    val open = "gte(t,${branchSeconds(run.start, span)})*lt(t,${branchSeconds(end, span)})"
    return FilterNode("colorchannelmixer", "aa" to "0", "enable" to "not($open)")
  }

  // What an animated overlay's branch has to be driven by. The animation is sampled once per output
  // frame of the run, and each option is written as the intervals it holds one value over. A
  // command written at exactly a frame's own time lands on that frame, so an interval opens on the
  // grid rather than anywhere nudged off it, and the clock is the branch's, the same one the enable
  // gate reads. An option is still only sent where its own value moves, and its intervals cover the
  // whole run, so a branch opened part way through reads what it is holding there instead of
  // whatever the graph configured the filter at.
  private fun ImageOverlay.commandsFor(
    attributes: Attributes,
    run: TimeRange,
  ): Driven {
    if (animation == null) return Driven.Still
    val rate = attributes.frameRate?.takeIf { it > 0f } ?: return Driven.Refused(ANIMATION_NO_GRID)
    val end = run.endExclusive ?: return Driven.Refused(ANIMATION_NO_RUN)
    val imageSize = measureOverlay(image) ?: return Driven.Refused(UNREADABLE_IMAGE)
    val span = attributes.span
    val outputSize = attributes.outputSize
    val base = placedOn(outputSize, imageSize)

    val last = frameIndexAt(end, span, rate)
    val sampled =
      (frameIndexAt(run.start, span, rate) until last).mapNotNull { index ->
        // A frame the sampler draws nothing on is left out rather than written as a change, so the
        // interval running across it carries on holding what it held.
        val frame = frameWithin(run, span.start + (index.toDouble() / rate).seconds) ?: return@mapNotNull null
        index to commandValues(frame, base.animatedBy(frame), outputSize)
      }
    // A run reaching no output frame, which is a window falling outside the span or one shorter
    // than a frame. There is nothing to drive, and the gate on the branch already draws nothing.
    if (sampled.isEmpty()) return Driven.Still

    val commands = sampled.first().second.keys
    val written =
      commands
        .flatMap { command -> sampled.holdsOf(command, last) }
        .sortedBy { it.from }
        .joinToString("") { hold ->
          "${commandSeconds(hold.from.toDouble() / rate)}-${commandSeconds(hold.until.toDouble() / rate)} " +
            "${hold.command};\n"
        }

    val name = instanceNameOf(written)
    return Driven.Commands(Sidecar(written.replace(INSTANCE_SLOT, name).encodeToByteArray(), "cmd"), name)
  }

  // One option's intervals across a sampled run: each opens on the frame the value moves to and
  // runs to the frame that moves it next, or to the end of the run for the last of them.
  private fun List<Pair<Int, Map<String, String>>>.holdsOf(
    command: String,
    end: Int,
  ): List<Hold> {
    val opens =
      filterIndexed { position, sample ->
        position == 0 || this[position - 1].second.driving(command) != sample.second.driving(command)
      }

    return opens.mapIndexed { position, (index, values) ->
      Hold(index, opens.getOrNull(position + 1)?.first ?: end, "$command ${values.driving(command)}")
    }
  }

  // The keys come off the first frame sampled, so a frame answering a different set of them would
  // otherwise write a file that drives some of the filters and leaves the rest where they were.
  private fun Map<String, String>.driving(command: String): String =
    this[command] ?: error("A sampled overlay frame carries no $command, so the commands cannot cover the run.")

  // What each driven filter is set to on one frame, keyed by the target and option the command
  // names, so each one can be written over the frames it holds. Every number is the shared
  // placement's: animatedBy scales both sides and moves the frame anchor, and the point inside the
  // overlay that the anchor holds is what turns the pair into a position.
  private fun commandValues(
    frame: OverlayFrame,
    drawn: OverlayPlacement,
    outputSize: Size,
  ): Map<String, String> {
    val width = drawn.size.width
    val height = drawn.size.height
    val anchorX = (drawn.frameAnchor.x * outputSize.width).roundToInt()
    val anchorY = (drawn.frameAnchor.y * outputSize.height).roundToInt()

    return mapOf(
      "colorchannelmixer$INSTANCE_SLOT aa" to alphaValue(frame.opacity),
      "scale$INSTANCE_SLOT w" to width.toString(),
      "scale$INSTANCE_SLOT h" to height.toString(),
      "overlay$INSTANCE_SLOT x" to (anchorX - (drawn.overlayAnchor.x * width).roundToInt()).toString(),
      "overlay$INSTANCE_SLOT y" to (anchorY - (drawn.overlayAnchor.y * height).roundToInt()).toString(),
    )
  }

  /**
   * The overlay image's stored pixel size, or null when this process cannot read it.
   *
   * ImageIO picks a reader off the stream's own magic bytes and answers the bounds out of the
   * header, so nothing here pulls the picture into memory. A URI is read as the file path the
   * backend materialises it to, which is the file ffmpeg itself opens.
   */
  private fun measureOverlay(image: ImageSource): Size? {
    val stream = image.openHeader() ?: return null

    return try {
      val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
      reader?.let {
        try {
          it.input = stream
          Size(it.getWidth(FIRST_IMAGE), it.getHeight(FIRST_IMAGE))
        } finally {
          it.dispose()
        }
      }
    } catch (unreadable: IOException) {
      null
    } finally {
      stream.close()
    }
  }

  private fun ImageSource.openHeader(): ImageInputStream? =
    try {
      when (this) {
        is ImageSource.Path -> {
          File(path).takeIf { it.isFile }?.let(ImageIO::createImageInputStream)
        }
        is ImageSource.Uri -> {
          File(
            uri.removePrefix(FILE_SCHEME),
          ).takeIf { it.isFile }?.let(ImageIO::createImageInputStream)
        }
        is ImageSource.Bytes -> {
          ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        }
      }
    } catch (unreadable: IOException) {
      null
    }

  // colorchannelmixer documents its coefficients in -2..2, which is this filter's own limit. The
  // shared sampler already answers an opacity in 0f..1f, so this pins that guarantee rather than
  // bending a value.
  private fun alphaValue(opacity: Float): String =
    String.format(Locale.ROOT, COMMAND_FORMAT, opacity.coerceIn(MIXER_FLOOR, MIXER_CEILING))

  // The first output frame at or after a composition time, counted on the branch's own clock. The
  // tolerance is a millionth of a frame, so a boundary that lands on the grid is not pushed onto
  // the next frame by the last bit of the division.
  private fun frameIndexAt(
    time: Duration,
    span: TimeRange,
    rate: Float,
  ): Int = ceil((time - span.start).toDouble(DurationUnit.SECONDS) * rate - GRID_TOLERANCE).toInt().coerceAtLeast(0)

  private fun commandSeconds(value: Double): String = String.format(Locale.ROOT, COMMAND_FORMAT, value)

  // The instance names have to be unique across the graph, and they are part of the file that
  // drives them, so the name is taken from a digest of the commands before they are named. Sidecar
  // already answers a digest of what it holds, so the throwaway one here is that hash rather than a
  // second file. Two overlays whose commands differ are named apart. Two whose commands are
  // identical share one name and one sidecar, which is harmless: identical commands draw the same
  // picture, so either branch's copy drives both the same way.
  private fun instanceNameOf(commands: String): String =
    "@fs" +
      Sidecar(commands.encodeToByteArray(), "cmd")
        .placeholder
        .trim('<', '>')
        .substringAfterLast('-')

  // One sendcmd interval, before it is written as text: the output frames it spans, and the single
  // command it sends on the frame it opens on.
  private class Hold(
    val from: Int,
    val until: Int,
    val command: String,
  )

  private sealed interface Driven {
    // Nothing to drive: an overlay holding still, or one whose run reaches no output frame.
    object Still : Driven

    class Refused(
      val message: String,
    ) : Driven

    class Commands(
      val sidecar: Sidecar,
      val instance: String,
    ) : Driven
  }

  // An image input is one frame at t = 0, and a branch carrying one frame ends on it. The overlay
  // is drawn for as long as the branch it merges onto, so the still is repeated across that whole
  // run. A pipeline that resolved neither a rate nor a bounded span has nothing to repeat it over.
  private fun Attributes.auxTimeline(): AuxTimeline? {
    val rate = frameRate?.takeIf { it > 0f } ?: return null
    val length = span.duration ?: return null

    return AuxTimeline(rate, length)
  }

  private fun textMessage(capabilities: RenderCapabilities): String =
    if (capabilities.has(RenderFeature.TextRendering)) TEXT_CANNOT_WRAP else TEXT_NO_FILTER

  private val Corner.isTrailing: Boolean
    get() = this == Corner.TopEnd || this == Corner.BottomEnd

  private val Corner.isBottom: Boolean
    get() = this == Corner.BottomStart || this == Corner.BottomEnd

  // Where a composition time lands on the branch the effect runs on, which is the clock every time
  // expression and every runtime command on that branch is read against.
  private fun branchSeconds(
    time: Duration,
    span: TimeRange,
  ): String = (time - span.start).toDouble(DurationUnit.SECONDS).toString()
}

// The token the instance names are written with while the commands are being built, since the name
// itself is a digest of what they say. It is replaced once, on the way into the file.
private const val INSTANCE_SLOT = "@fs?"

// Six decimals. A frame time on any rate this backend writes stops moving well before that, and it
// is what a command's own value is written to as well, so one format serves both.
private const val COMMAND_FORMAT = "%.6f"

// colorchannelmixer's documented coefficient range, which is this filter's own limit.
private const val MIXER_FLOOR = -2f
private const val MIXER_CEILING = 2f

// A millionth of a frame, so a run boundary already sitting on the grid is not read as the frame
// after it by the last bit of the division.
private const val GRID_TOLERANCE = 1e-6

// The image in an ImageIO stream, which is the only one a still carries.
private const val FIRST_IMAGE = 0

// What Scratch strips from a URI before handing the path to ffmpeg, so a header read here opens the
// same file the graph does.
private const val FILE_SCHEME = "file://"

private const val ANIMATION_NO_GRID =
  "An animated overlay is sampled once per output frame, and this composition resolved no frame " +
    "rate to sample it onto. Give the export a frame rate, or drop the animation to draw the " +
    "overlay still."

private const val ANIMATION_NO_RUN =
  "An animated overlay is sampled across the run it is drawn over, and this composition names no " +
    "end for that run. Give the export a duration, or drop the animation to draw the overlay still."

private const val UNREADABLE_IMAGE =
  "The overlay image could not be read. An animated overlay is sized against the picture's own " +
    "pixels, so this backend reads its header. Check that the path or URL is readable by this " +
    "process, and that the bytes are PNG, JPEG or another format the JDK decodes."

// The only depth this backend writes HDR at, so the lut runs on planar RGB of the same depth
// rather than on whatever the auto-negotiated conversion would have picked.
private const val HDR_PLANAR_RGB = "gbrp10le"

// The format a graded signal is held in between the two halves of a transfer function, so that a
// table read at ten bits still lands on the code value. Packed rather than planar because swscale
// converts gbrp16le to gbrp10le a code out on a third of all colours, while rgb48le round trips
// exactly, and because lutrgb's maxval on this one is the format's own peak.
private const val WIDE_RGB = "rgb48le"

// The top code each of the two formats holds, which is what a graded table is scaled by. lutrgb's
// own maxval is 255 shifted up to the depth, so it agrees on rgb48le and reads three low on
// gbrp10le.
private const val HDR_PLANAR_PEAK = 1023
private const val WIDE_RGB_PEAK = 65535

// White is the top of an SDR signal, so a table written for one scales its bias by nothing.
private const val SDR_CEILING = 1f

// Two points a side is the whole of an affine map, and every entry between them is interpolated
// rather than stored.
private const val CUBE_SIZE = 2
private const val CUBE_ENTRY_FORMAT = "%.7f"

private const val FULL_TURN = 360
private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270

private const val PAN_PENDING =
  "A pan moves the region it shows on every frame, and this backend resolves a crop to whole " +
    "pixels once at plan time so that the plan and the export cannot disagree about the frame. " +
    "The time-varying form has not landed here yet."

private const val TEXT_NO_FILTER =
  "This ffmpeg build has no drawtext filter, so text cannot be burned in. drawtext needs " +
    "--enable-libfreetype and --enable-libharfbuzz, which the common prebuilt packages leave " +
    "out. Export without the caption, or install a build that has it."

private const val TEXT_CANNOT_WRAP =
  "drawtext breaks lines only on a literal newline, so TextStyle.maxWidth cannot be honoured " +
    "and line breaks would land on different words than every other backend. TextOverlay layout is " +
    "required to be exact, so it is refused here rather than rendered differently."
