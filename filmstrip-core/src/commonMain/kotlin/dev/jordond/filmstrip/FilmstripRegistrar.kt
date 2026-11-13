package dev.jordond.filmstrip

import dev.jordond.filmstrip.internal.platformRegistrars
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Registers one artifact's components on a [FilmstripBuilder].
 *
 * An artifact ships one of these and makes it discoverable, through `ServiceLoader` on the JVM and Android and through
 * [FilmstripRegistrars] on every other target. A builder applies each discovered registrar when it builds, unless its
 * block installed that registrar already or turned discovery off with [FilmstripBuilder.autoRegister].
 *
 * A JVM implementation is a class with a public no-argument constructor, since `ServiceLoader` creates it.
 *
 * @property id The artifact's name. A builder installs at most one registrar per id.
 */
@InternalFilmstripApi
public interface FilmstripRegistrar {
  public val id: String

  /**
   * Adds this artifact's components to [builder].
   *
   * @param builder The builder the components go on.
   */
  public fun register(builder: FilmstripBuilder)
}

/**
 * The registrars artifacts add for themselves when they load.
 *
 * Kotlin/Native and the browser targets have no `ServiceLoader`, so an artifact adds its registrar here from an
 * eagerly initialised top-level property instead. On the JVM and Android an artifact can do either.
 */
@InternalFilmstripApi
@OptIn(ExperimentalAtomicApi::class)
public object FilmstripRegistrars {
  private val added = AtomicReference(emptyList<FilmstripRegistrar>())

  /**
   * Adds [registrar] to the ones every builder discovers.
   *
   * @param registrar The registrar to add. A second one with the same id is ignored.
   */
  public fun register(registrar: FilmstripRegistrar) {
    while (true) {
      val current = added.load()
      if (current.any { it.id == registrar.id }) return
      if (added.compareAndSet(current, current + registrar)) return
    }
  }

  // Sorted by id, so the order a builder applies them in does not hang on the classpath or on load order.
  internal fun discovered(): List<FilmstripRegistrar> =
    (added.load() + platformRegistrars()).distinctBy { it.id }.sortedBy { it.id }

  internal fun remove(id: String) {
    while (true) {
      val current = added.load()
      if (added.compareAndSet(current, current.filterNot { it.id == id })) return
    }
  }
}

/**
 * A typed value a [FilmstripBuilder] carries for the registrars it applies.
 *
 * An artifact declares its key, a block sets a value with [FilmstripBuilder.option], and the artifact's registrar reads
 * it with [FilmstripBuilder.optionOrNull]. Keys compare by identity.
 *
 * @property name What the option is called, for a reader of a dump.
 */
@InternalFilmstripApi
public class OptionKey<T : Any>(
  public val name: String,
) {
  override fun toString(): String = "OptionKey($name)"
}
