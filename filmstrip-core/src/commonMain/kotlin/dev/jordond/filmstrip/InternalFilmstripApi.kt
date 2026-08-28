package dev.jordond.filmstrip

/**
 * Marks the annotated element as internal to filmstrip.
 *
 * It is not meant to be used outside the filmstrip modules, and it is excluded from the apiDump, so
 * anything it marks can change in a patch release.
 */
@Target(
  allowedTargets = [
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPEALIAS,
  ],
)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
public annotation class InternalFilmstripApi
