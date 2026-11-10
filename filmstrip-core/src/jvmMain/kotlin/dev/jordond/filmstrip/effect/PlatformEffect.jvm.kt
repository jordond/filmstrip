package dev.jordond.filmstrip.effect

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.ImageSource
import java.security.MessageDigest
import kotlin.time.Duration

/**
 * The JVM form: a fragment of a filter graph.
 *
 * The platform object here is data rather than a GPU handle, so a resolver is a pure function from
 * a spec to a value and can be asserted on in a unit test with no toolchain installed. How a
 * fragment is spelled on a command line belongs to the backend that runs it, not here.
 *
 * @property fragment What this effect contributes to the graph.
 */
@Poko
public actual class PlatformEffect(
  public val fragment: FilterFragment,
)

/**
 * One effect's contribution to a filter graph.
 *
 * Most effects are a [chain] on the pad they are handed. An overlay also brings its own input, so
 * [auxInputs] are prepared by their own chains and then consumed by a single [merge] node that
 * takes the main pad plus each of them.
 *
 * A fragment that only touches part of the frame names a [crop]. Everything else then runs on the
 * cropped region, and the result is laid back into the full frame where the crop took it from.
 * Nodes that must run after the merge go in [post].
 *
 * @property chain Nodes applied in order to the pad this effect receives.
 * @property auxInputs Extra inputs this effect needs, each with its own preparation chain.
 * @property merge The node that consumes the main pad and every [auxInputs] entry, producing one
 * pad. Null when there is nothing to merge. An `overlay` merge has its `format` set by the backend to match the frame
 * it draws on and its `alpha` set to straight, and its own `format` and `alpha` are dropped. On an 8-bit 4:2:0 export
 * that format is `yuv420`, where `x` and `y` are moved down onto the even pixel grid ffmpeg places the picture on.
 * @property sidecars Files this effect needs on disk before the graph runs. A node references one
 * by writing its [Sidecar.placeholder] where the path belongs, and the backend swaps in the path it
 * wrote the bytes to.
 * @property crop The region this effect works on, cut out of a copy of the pad it receives.
 * [chain], [merge] and [post] run on that region, which then replaces the pixels it was cut from,
 * alpha included, at the position it was cut from. Null to work on the whole pad.
 * A worked region that carries alpha is not blended over the original by it, so a region that reads
 * the pad's own alpha has to write the alpha the pixels end up with.
 * @property post Nodes applied in order to the pad [merge] produces, or to the [chain]'s output
 * when there is no [merge]. With a [crop], the last of them writes the region laid back, so the
 * alpha it leaves on that region is the alpha the pad ends up with there.
 * @property branches A graph of its own this effect runs instead of [chain], for one that needs the frame more than
 * once. The last branch writes the pad the effect hands on.
 * @property readsCompositionClock Whether the nodes read `t` or `T` as composition time. The backend moves the frame's
 * timestamps onto the composition's clock around them, so an effect never has to know where its clip lands or where a
 * preview window opened.
 * @property live The same graph as [branches], written to read its costly map from a [LiveInput] where the backend can
 * reach one. Null for a fragment that has no such form.
 */
@Poko
public class FilterFragment
  @InternalFilmstripApi
  constructor(
    public val chain: List<FilterNode> = emptyList(),
    public val auxInputs: List<AuxInput> = emptyList(),
    public val merge: FilterNode? = null,
    public val sidecars: List<Sidecar> = emptyList(),
    public val crop: FilterCrop? = null,
    public val post: List<FilterNode> = emptyList(),
    public val branches: List<FilterBranch> = emptyList(),
    public val readsCompositionClock: Boolean = false,
    @InternalFilmstripApi public val live: LiveBranches?,
  ) {
    /**
     * Builds a fragment with no live form.
     *
     * @param chain Nodes applied in order to the pad this effect receives.
     * @param auxInputs Extra inputs this effect needs.
     * @param merge The node that consumes the main pad and every [auxInputs] entry.
     * @param sidecars Files this effect needs on disk before the graph runs.
     * @param crop The region this effect works on.
     * @param post Nodes applied in order after [merge] or [chain].
     * @param branches A graph of its own this effect runs instead of [chain].
     * @param readsCompositionClock Whether the nodes read `t` or `T` as composition time.
     */
    public constructor(
      chain: List<FilterNode> = emptyList(),
      auxInputs: List<AuxInput> = emptyList(),
      merge: FilterNode? = null,
      sidecars: List<Sidecar> = emptyList(),
      crop: FilterCrop? = null,
      post: List<FilterNode> = emptyList(),
      branches: List<FilterBranch> = emptyList(),
      readsCompositionClock: Boolean = false,
    ) : this(chain, auxInputs, merge, sidecars, crop, post, branches, readsCompositionClock, null)
  }

/**
 * A [FilterFragment]'s graph with a live plane standing in for a map the tool would otherwise draw.
 *
 * Its samples match [FilterFragment.branches], so a backend free to run either picks by cost.
 *
 * @property inputs The planes the graph reads, each on the pad it names.
 * @property branches The graph, which reads each input's [LiveInput.pad] like any other pad.
 * @property needsBt709Frames Whether [branches] holds the match only for frames tagged BT.709. A filter reading two
 * pads settles one matrix for both, and a plane with chroma of its own moves its luma when it is converted onto
 * another.
 */
@InternalFilmstripApi
@Poko
public class LiveBranches(
  public val inputs: List<LiveInput>,
  public val branches: List<FilterBranch>,
  public val needsBt709Frames: Boolean = false,
)

/**
 * A plane per tick a backend hands to the tool as a video input while the graph runs.
 *
 * The plane lands as 8 bit limited range BT.709 luma, in a frame whose chroma is neutral, with each frame stamped on
 * its tick of the composition's clock.
 *
 * Two inputs are equal when their [key], size and rate are, which [planeAt] has to make a promise of.
 *
 * @property pad The local pad a [LiveBranches] graph reads this input from.
 * @property width The plane's width in pixels.
 * @property height The plane's height in pixels.
 * @property ticksPerSecond How many planes a second of the composition's clock holds.
 * @property key What names the planes this input draws.
 * @property planeAt Draws one tick's plane, [width] by [height] bytes in reading order. It runs off the graph's own
 * threads and has to be safe to call from several at once.
 * @property tickOpening Writes the expression for the whole microsecond a tick opens on, from an expression naming the
 * tick. It is the same clock the fragment's own reads of a frame's time land on.
 * @property carriesChroma Whether [planeAt] draws a whole 4:2:0 frame, chroma behind luma, rather than luma alone.
 */
@InternalFilmstripApi
public class LiveInput(
  public val pad: String,
  public val width: Int,
  public val height: Int,
  public val ticksPerSecond: Double,
  public val key: String,
  public val planeAt: (tick: Int) -> ByteArray,
  public val tickOpening: (tick: String) -> String,
  public val carriesChroma: Boolean = false,
) {
  override fun equals(other: Any?): Boolean =
    other is LiveInput &&
      other.pad == pad &&
      other.width == width &&
      other.height == height &&
      other.ticksPerSecond == ticksPerSecond &&
      other.key == key &&
      other.carriesChroma == carriesChroma

  override fun hashCode(): Int = listOf(pad, width, height, ticksPerSecond, key, carriesChroma).hashCode()
}

/**
 * One chain of a [FilterFragment.branches] graph, or a fan out when it writes more than one pad.
 *
 * Pads are named locally, and the backend makes them unique in the graph it builds.
 *
 * @property reads The pads this branch reads, where [INPUT] is the pad the effect receives.
 * @property nodes The nodes applied in order. Empty on a fan out, which copies its one read to every pad in [writes].
 * @property writes The pads this branch writes.
 */
@Poko
public class FilterBranch(
  public val reads: List<String>,
  public val nodes: List<FilterNode>,
  public val writes: List<String>,
) {
  public companion object {
    /**
     * The name of the pad the effect receives.
     */
    public const val INPUT: String = "in"
  }
}

/**
 * The region a [FilterFragment] works on, as the tool's `crop` filter would cut it.
 *
 * Every value is an expression the tool evaluates against the pad, such as `320` or `iw/2`.
 *
 * @property width The region's width.
 * @property height The region's height.
 * @property x Where the region's left edge sits, or null to centre it across the pad.
 * @property y Where the region's top edge sits, or null to centre it down the pad.
 * @property exact Whether the region is cut at exactly [x] and [y], rather than at the nearest
 * position on the chroma grid of the pad it is cut from.
 */
@Poko
public class FilterCrop(
  public val width: String,
  public val height: String,
  public val x: String? = null,
  public val y: String? = null,
  public val exact: Boolean = true,
)

/**
 * One filter, and the arguments it is configured with.
 *
 * Values are unescaped. The backend escapes them when it renders the graph, following the rules
 * of whichever tool consumes it.
 *
 * @property name The filter's name.
 * @property arguments The filter's arguments, in the order they are written.
 */
@Poko
public class FilterNode(
  public val name: String,
  public val arguments: List<FilterArgument> = emptyList(),
) {
  /**
   * Builds a node from `key to value` pairs.
   *
   * @param name The filter's name.
   * @param arguments The filter's arguments, in the order they are written.
   */
  public constructor(
    name: String,
    vararg arguments: Pair<String, String>,
  ) : this(name, arguments.map { (key, value) -> FilterArgument(key, value) })
}

/**
 * One `key=value` argument to a [FilterNode].
 *
 * @property key The argument's name.
 * @property value The argument's value, unescaped.
 */
@Poko
public class FilterArgument(
  public val key: String,
  public val value: String,
)

/**
 * An extra input a [FilterFragment] needs, such as the image an overlay composites.
 *
 * Carries the [ImageSource] rather than a path. The backend decides where the bytes land before a
 * tool that reads files can see them.
 *
 * @property image The image to read.
 * @property chain Nodes applied to the image before it reaches [FilterFragment.merge].
 * @property timeline The run this input's branch has to cover, or null to read the image once.
 */
@Poko
public class AuxInput(
  public val image: ImageSource,
  public val chain: List<FilterNode> = emptyList(),
  public val timeline: AuxTimeline? = null,
)

/**
 * How long an [AuxInput]'s branch runs for, and at what rate it advances.
 *
 * A branch fed by a single still ends on its first frame, which leaves a time-varying filter on it
 * with nothing to run on and lets the merge downstream stop drawing it. Repeating the still at
 * [frameRate] for [duration] keeps the branch alive for as long as the one it merges onto.
 *
 * [duration] is counted from the branch's own start rather than from the composition's, so a clip
 * effect names its clip's length and a composition effect names the whole composition's.
 *
 * @property frameRate Frames per second the branch advances at.
 * @property duration How long the branch runs.
 */
@Poko
public class AuxTimeline(
  public val frameRate: Float,
  public val duration: Duration,
)

/**
 * A file an effect needs the tool to read, such as the lookup table a color grade lowers to.
 *
 * Carries the bytes rather than a path, the way [AuxInput] carries an image. The backend writes
 * them into its scratch directory before the graph runs, and a node reaches the file by writing
 * [placeholder] where the path belongs.
 *
 * @property bytes What the file holds.
 * @property extension The file's extension without its dot, since a tool that reads the format off
 * the name needs one.
 */
@Poko
public class Sidecar(
  @Poko.ReadArrayContent public val bytes: ByteArray,
  public val extension: String,
) {
  /**
   * What a node writes where the path goes.
   *
   * Derived from the contents and the extension, which is what this class is equal by, so two
   * effects that need the same file share a placeholder and two that need the same bytes under
   * different names do not. Spelled with characters no filter graph escapes, so it survives into the
   * rendered text as written.
   */
  public val placeholder: String = "<sidecar-" + digest(bytes, extension) + ">"
}

// The leading bytes of a SHA-256 of the contents and the extension, spelled as hex, so two files a
// graph reads never share a name.
private fun digest(
  bytes: ByteArray,
  extension: String,
): String =
  MessageDigest
    .getInstance("SHA-256")
    .apply { update(extension.encodeToByteArray()) }
    .digest(bytes)
    .take(DIGEST_BYTES)
    .joinToString("") { byte -> (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }

private const val DIGEST_BYTES = 8
private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
