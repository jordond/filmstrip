package dev.jordond.filmstrip.effect

/**
 * The Android form, holding an `androidx.media3.common.Effect` as an untyped handle.
 *
 * The rendering backends cast [handle] back before use, and fail with a message naming the spec if
 * it holds anything else.
 *
 * @property handle The media3 `Effect` this wraps.
 */
public actual class PlatformEffect(
  public val handle: Any,
)
