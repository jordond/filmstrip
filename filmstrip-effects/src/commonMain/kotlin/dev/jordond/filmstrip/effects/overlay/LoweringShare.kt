package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.media.ImageSource
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What one lowering built from each animated picture it names, so a picture named by several overlays is composited and
 * held once.
 *
 * [readFor] replaces the whole set ahead of a lowering and a value is built again every time, so nothing carries into a
 * later lowering. A picture the lowering does not name is built on the spot and not held. A picture is held once for
 * each distinct [K], such as the grade its frames are mapped for, and overlays asking at the same time wait for the one
 * build.
 *
 * @param K What a built value depends on besides the picture.
 * @param V What is built from one picture.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LoweringShare<K, V : Any> {
  private val state = AtomicReference(Shared<K, V>(emptySet(), emptyMap()))

  /**
   * Forgets every value built so far and holds the pictures [composition] names.
   */
  fun readFor(composition: Composition) {
    val sources = overlayEffectsOf(composition).filterIsInstance<ImageOverlay>().map { it.image }.toSet()
    state.store(Shared(sources, emptyMap()))
  }

  /**
   * The value of [source] for [key], from [build] the first time a lowering asks for it.
   */
  fun valueFor(
    source: ImageSource,
    key: K,
    build: () -> V,
  ): V {
    val at = source to key
    while (true) {
      val current = state.load()
      if (source !in current.named) return build()
      current.built[at]?.let { return it.value }
      val made = lazy(LazyThreadSafetyMode.SYNCHRONIZED, build)
      if (state.compareAndSet(current, Shared(current.named, current.built + (at to made)))) return made.value
    }
  }

  private class Shared<K, V : Any>(
    val named: Set<ImageSource>,
    val built: Map<Pair<ImageSource, K>, Lazy<V>>,
  )
}
