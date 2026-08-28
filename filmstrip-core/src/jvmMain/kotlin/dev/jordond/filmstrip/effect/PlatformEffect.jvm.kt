package dev.jordond.filmstrip.effect

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.media.ImageSource

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
 */
@Poko
public class FilterFragment(
  public val chain: List<FilterNode> = emptyList(),
  public val auxInputs: List<AuxInput> = emptyList(),
  public val merge: FilterNode? = null,
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
