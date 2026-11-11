package dev.jordond.filmstrip.audio

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.effect.FilterNode

/**
 * The JVM form: a run of audio filters for a filter graph.
 *
 * The backend here hands the edit to a tool that cannot run an [AudioKernel], so an effect is written as the tool's
 * own filters. The object is data, so a resolver is a pure function that a unit test can assert on.
 *
 * @property filters Nodes applied in order to the stream this effect receives, each taking one audio pad and producing
 * one.
 */
@Poko
public actual class PlatformAudioEffect(
  public val filters: List<FilterNode>,
)
