package dev.jordond.filmstrip.player

/**
 * Skips a test on the iOS simulator target, where it reports as ignored. Every other Apple target runs it.
 */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
internal expect annotation class IgnoreOnSimulator()
