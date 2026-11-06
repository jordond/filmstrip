package dev.jordond.filmstrip.player

import platform.Foundation.NSProcessInfo

/**
 * Skips a test on the iOS simulator target, where it reports as ignored. Every other Apple target runs it.
 */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
internal expect annotation class IgnoreOnSimulator()

/**
 * Whether this process runs on a simulator, read off the variable a simulator sets for what it spawns.
 */
internal fun runsOnSimulator(): Boolean = NSProcessInfo.processInfo.environment["SIMULATOR_UDID"] != null
