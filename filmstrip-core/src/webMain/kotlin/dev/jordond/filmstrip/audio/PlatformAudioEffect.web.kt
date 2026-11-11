package dev.jordond.filmstrip.audio

import dev.jordond.filmstrip.export.AudioFormat

/**
 * The browser form: a factory for the [AudioKernel] the backend runs on the decoded samples.
 *
 * @property newKernel Builds the kernel for one stream. It runs once for every stream the effect processes, with that
 * stream's sample rate and channel count, and has to answer a new kernel each time, since a kernel holds one stream's
 * state. A preview and an export of one edit may ask at different formats.
 */
public actual class PlatformAudioEffect(
  public val newKernel: (AudioFormat) -> AudioKernel,
)
