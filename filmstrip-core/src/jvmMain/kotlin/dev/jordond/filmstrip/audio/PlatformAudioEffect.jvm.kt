package dev.jordond.filmstrip.audio

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.export.AudioFormat

/**
 * The JVM form: a factory for the run of audio filters the backend writes into a filter graph.
 *
 * The backend here hands the edit to a tool that cannot run an [AudioKernel], so an effect is written as the tool's
 * own filters.
 *
 * @property newFilters Builds the nodes for one stream, with that stream's sample rate and channel count. The nodes
 * are applied in order to the stream, each taking one audio pad and producing one.
 */
public actual class PlatformAudioEffect(
  public val newFilters: (AudioFormat) -> List<FilterNode>,
) {
  /**
   * An effect whose filters are the same whatever format the stream has.
   *
   * @param filters Nodes applied in order to the stream this effect receives, each taking one audio pad and producing
   * one.
   */
  public constructor(filters: List<FilterNode>) : this({ filters })
}
