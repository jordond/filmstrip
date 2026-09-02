package dev.jordond.filmstrip.effect

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.media.ImageSource
import java.security.MessageDigest

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
 * @property chain Nodes applied in order to the pad this effect receives.
 * @property auxInputs Extra inputs this effect needs, each with its own preparation chain.
 * @property merge The node that consumes the main pad and every [auxInputs] entry, producing one
 *   pad. Null when there is nothing to merge.
 * @property sidecars Files this effect needs on disk before the graph runs. A node references one
 * by writing its [Sidecar.placeholder] where the path belongs, and the backend swaps in the path it
 * wrote the bytes to.
 */
@Poko
public class FilterFragment(
  public val chain: List<FilterNode> = emptyList(),
  public val auxInputs: List<AuxInput> = emptyList(),
  public val merge: FilterNode? = null,
  public val sidecars: List<Sidecar> = emptyList(),
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
 */
@Poko
public class AuxInput(
  public val image: ImageSource,
  public val chain: List<FilterNode> = emptyList(),
)

/**
 * A file an effect needs the tool to read, such as the lookup table a colour grade lowers to.
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
