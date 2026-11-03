package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
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
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.filterGraphOverlay
import dev.jordond.filmstrip.effects.overlay.filterGraphText
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import java.util.Locale

/**
 * Lowers the built-in catalogue onto a filter graph.
 *
 * Every lowering is a pure function of the spec and the resolved [Attributes], and needs no
 * toolchain installed. An overlay is the one lowering that reads anything. An image overlay needs the picture's own
 * pixel size to place it. One on an HDR grade decodes the picture to carry it onto the grade's signal, and one on an
 * SDR grade decodes a picture that describes its color, or a JPEG XL, to draw it in that color. A text overlay is
 * drawn to a picture with the JVM's own fonts and placed the same way.
 * The backend's [OverlayPictureDecoder] decodes and sizes every picture when there is one, and the
 * JDK reads them when there is none.
 */
public actual class BuiltInEffectResolver : EffectResolver {
  private val pictureDecoder: OverlayPictureDecoder?

  public actual constructor() {
    pictureDecoder = null
  }

  /**
   * Creates the catalogue for a backend that reads overlay pictures the JDK cannot.
   *
   * The ffmpeg backend builds its catalogue this way when it finds a [BuiltInEffectResolver] in the resolver list it
   * was given. It matches that type exactly, so a resolver of your own that wraps a [BuiltInEffectResolver] keeps the
   * JDK-only reads unless it builds the one it wraps with this constructor. Without a decoder, an animated overlay, one
   * on an HDR grade and one that describes its color are refused when the picture is in a format the JDK has no
   * reader for, such as WebP, an AVIF or HEIC is refused on every grade, since the graph cannot repeat one as it is,
   * and a JPEG XL on an SDR grade is drawn without looking for a color description in it.
   *
   * With a decoder, a URL only ffmpeg opens, such as an `https` one, is drawn from its decode, and refused when that
   * decode describes its color, since this process cannot read the description its container carries.
   *
   * @param pictureDecoder Decodes and sizes every overlay picture the catalogue reads.
   */
  @InternalFilmstripApi
  public constructor(pictureDecoder: OverlayPictureDecoder) {
    this.pictureDecoder = pictureDecoder
  }

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
      is ImageOverlay -> filterGraphOverlay(spec, attributes, pictureDecoder)
      is TextOverlay -> filterGraphText(spec, capabilities, attributes)
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

  // Every color effect in the catalogue is one affine map of the encoded signal, and it reaches
  // the graph through whichever of two filters spells that map exactly. colorchannelmixer is
  // neither: it fails the graph above a gain of 2 rather than clamping, and it carries no bias.
  //
  // On a kept grade the same map runs on the SDR signal the code value's light decodes to, with
  // the transfer function either side of it, on the grade's own Y'CbCr. The frame's codes are
  // read off float planes and every step between them and the codes written back runs on float
  // tables and mixer rows, so no code is rounded in between.
  private fun colorMatrix(
    spec: EffectSpec,
    transfer: HdrTransfer?,
  ): EffectResolution {
    val matrix = checkNotNull(colorMatrixOf(spec)) { "${spec.id} has no matrix to lower." }
    return when {
      // Claimed rather than declined, the way Scale is, because an unclaimed spec is refused by
      // name at plan time.
      matrix.isIdentity -> fragment(emptyList())
      transfer != null -> EffectResolution.Resolved(PlatformEffect(gradedOnTables(matrix, transfer)))
      !matrix.isDiagonal -> cubeLut(matrix)
      else -> fragment(listOf(diagonalLut(matrix)))
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
  private fun cubeLut(matrix: ColorMatrix): EffectResolution {
    val sidecar = Sidecar(cube(matrix).encodeToByteArray(), "cube")

    return EffectResolution.Resolved(
      PlatformEffect(
        FilterFragment(
          chain = listOf(FilterNode("lut3d", "file" to sidecar.placeholder)),
          sidecars = listOf(sidecar),
        ),
      ),
    )
  }

  // The eight corners of the unit cube, red varying fastest and blue slowest, which is the order a
  // .cube file is read in. Entries are left unclamped so that a corner the matrix pushes past white
  // still lands on the line the interpolation between it and its neighbour needs.
  private fun cube(matrix: ColorMatrix): String =
    buildString {
      appendLine("LUT_3D_SIZE $CUBE_SIZE")
      for (blue in 0..1) {
        for (green in 0..1) {
          for (red in 0..1) {
            val r = matrix.rr * red + matrix.rg * green + matrix.rb * blue + matrix.rBias
            val g = matrix.gr * red + matrix.gg * green + matrix.gb * blue + matrix.gBias
            val b = matrix.br * red + matrix.bg * green + matrix.bb * blue + matrix.bBias
            appendLine("${entry(r)} ${entry(g)} ${entry(b)}")
          }
        }
      }
    }

  // Fixed decimals in the root locale. A comma for the decimal point, or an exponent on a small
  // enough entry, is a file ffmpeg reads as something else.
  private fun entry(value: Float): String = String.format(Locale.ROOT, CUBE_ENTRY_FORMAT, value)

  // Pixels are multiplied out here with the planner's own arithmetic, so plan() and the export
  // agree about the output frame.
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
}

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
