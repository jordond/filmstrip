package dev.jordond.filmstrip.effect

/**
 * A platform's own realisation of one [EffectSpec], opaque to common code.
 *
 * Produced by an [EffectResolver] and consumed by whichever backend is rendering. Never implemented
 * by a consumer, and never shared between two pipelines.
 */
public expect class PlatformEffect
