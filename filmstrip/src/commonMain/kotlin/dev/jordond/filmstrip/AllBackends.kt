package dev.jordond.filmstrip

import dev.jordond.filmstrip.player.playerBackend

/**
 * Registers every backend this artifact bundles: the preview backend and this target's export backend.
 *
 * Both register themselves, so a builder only calls this with discovery turned off.
 *
 * ```kotlin
 * val filmstrip = Filmstrip {
 *   autoRegister(false)
 *   allBackends()
 * }
 * ```
 *
 * Registrations made after it in the same block override a bundled one. Without this artifact the same setup is
 * `playerBackend()` from `filmstrip-player` plus the export backend from its own engine artifact: `media3Backend()`,
 * `avFoundationBackend()`, `webCodecsBackend()` or `ffmpegBackend()`.
 *
 * @return This builder.
 */
public fun FilmstripBuilder.allBackends(): FilmstripBuilder = playerBackend().transformBackend()

/**
 * Registers the export backend for the current target.
 *
 * That is media3 on Android, AVFoundation on Apple, WebCodecs in the browser and ffmpeg on the JVM,
 * the only one each target has. It registers itself, so a builder only calls this with discovery turned off.
 * `filmstrip-player` already brings that backend in, so a player consumer can register it without this artifact
 * through `media3Backend()`, `avFoundationBackend()`, `webCodecsBackend()` or `ffmpegBackend()`.
 */
public fun FilmstripBuilder.transformBackend(): FilmstripBuilder = installDefaultTransform()

internal expect fun FilmstripBuilder.installDefaultTransform(): FilmstripBuilder
