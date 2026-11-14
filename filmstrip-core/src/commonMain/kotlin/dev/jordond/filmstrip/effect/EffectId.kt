package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.audio.AudioEffectSpec
import kotlin.jvm.JvmInline

/**
 * Names one kind of effect, video or audio.
 *
 * Read it off [EffectSpec.id] or [AudioEffectSpec.id]. Namespace the value like a package, `acme.filmgrain`, so two
 * libraries cannot collide. Never derive it from the class name, which changes under refactoring.
 *
 * @property value The identifier as text, the way it reads in a message or a diagnostic.
 */
@JvmInline
public value class EffectId(
  public val value: String,
) {
  /**
   * Prints the identifier as it was given.
   *
   * @return The [value], with nothing added.
   */
  override fun toString(): String = value
}
