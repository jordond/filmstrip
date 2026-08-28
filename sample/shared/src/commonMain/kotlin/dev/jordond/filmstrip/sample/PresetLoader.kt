package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSource

/**
 * Fetches [preset] and hands back the source this platform's backend reads.
 *
 * Where there is a filesystem the download is cached under the temporary directory, so picking the
 * same clip twice only downloads once.
 */
public expect suspend fun loadPreset(preset: SamplePreset): MediaSource

/**
 * Whether this platform can fetch the clips at all.
 */
internal expect val presetsAvailable: Boolean
