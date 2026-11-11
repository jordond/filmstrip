package dev.jordond.filmstrip.audio

/**
 * A platform's own realization of one [AudioEffectSpec], opaque to common code.
 *
 * Produced by an [AudioEffectResolver] and consumed by whichever backend is running.
 */
public expect class PlatformAudioEffect
