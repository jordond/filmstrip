package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import platform.Foundation.NSProcessInfo

/**
 * Whether this process is running on the iOS simulator.
 *
 * The simulator sets `SIMULATOR_DEVICE_NAME` in every process it launches, while macOS and a real iPhone leave it
 * unset, so this tells the simulator apart from both.
 */
@InternalFilmstripApi
public fun isSimulator(): Boolean = NSProcessInfo.processInfo.environment[SIMULATOR_DEVICE_NAME] != null

private const val SIMULATOR_DEVICE_NAME = "SIMULATOR_DEVICE_NAME"
