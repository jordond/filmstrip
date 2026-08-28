package dev.jordond.filmstrip

import dev.jordond.filmstrip.playback.playerBackend

/**
 * Creates a [Filmstrip] with every backend this artifact bundles already registered.
 *
 * The same thing, registered by hand from `filmstrip-core`:
 *
 * ```kotlin
 * val filmstrip = Filmstrip {
 *   playerBackend()
 *   transformBackend()
 * }
 * ```
 *
 * [block] runs after the bundled backends, so a resolver registered there overrides a built-in.
 *
 * @param block Registers anything extra on the builder.
 * @return The configured instance.
 */
public fun Filmstrip.Companion.create(block: FilmstripBuilder.() -> Unit = {}): Filmstrip =
  FilmstripBuilder().allBackends().apply(block).build()

/**
 * Registers the preview backend and the export backend.
 *
 * The composable form, for a consumer assembling a builder themselves.
 */
public fun FilmstripBuilder.allBackends(): FilmstripBuilder = playerBackend().transformBackend()

/**
 * Registers this artifact's default export backend for the current target.
 *
 * media3 on Android, AVFoundation on Apple, WebCodecs in the browser and ffmpeg on the JVM. Take a
 * single engine artifact instead to choose a different one.
 */
public fun FilmstripBuilder.transformBackend(): FilmstripBuilder = installDefaultTransform()

internal expect fun FilmstripBuilder.installDefaultTransform(): FilmstripBuilder
