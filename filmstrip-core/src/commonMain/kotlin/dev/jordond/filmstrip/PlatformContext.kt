package dev.jordond.filmstrip

/**
 * Whatever the platform needs to read media and reach its codecs.
 *
 * On Android this wraps an `android.content.Context`. Elsewhere it is a no-op.
 */
public expect class PlatformContext

internal expect fun platformContext(): PlatformContext
