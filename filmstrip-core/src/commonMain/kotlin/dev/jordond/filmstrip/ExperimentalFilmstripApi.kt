package dev.jordond.filmstrip

/**
 * Marks an API that is published but not yet settled.
 *
 * Either its shape is still moving, or the platform machinery underneath it is.
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
public annotation class ExperimentalFilmstripApi
