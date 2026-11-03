package dev.jordond.filmstrip.effects.overlay

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue

/**
 * Asserts this is an instance of the Objective-C class [T] and returns it cast.
 *
 * kotest's `shouldBeInstanceOf` checks through a `KClass`, which does not see a Foundation object Kotlin wraps on
 * the way in, like a dictionary that arrives as `NSDictionaryAsKMap`. This checks with `is`, which does.
 */
inline fun <reified T : Any> Any?.shouldBeObjC(): T {
  withClue("$this is not a ${T::class.simpleName}") { (this is T).shouldBeTrue() }
  return this as T
}
