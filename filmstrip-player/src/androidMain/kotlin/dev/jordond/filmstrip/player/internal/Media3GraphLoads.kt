package dev.jordond.filmstrip.player.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps what a graph reports from reaching the engine once a rebuild has replaced that graph.
 *
 * A rebuild issues a load on the engine's dispatcher and hands it over on the player's thread just before media3 takes
 * the new composition. Each report carries the load handed over when the player's thread raised it, and is dropped on
 * the engine's dispatcher unless that is still the load issued last. That covers a report already queued on the engine
 * when the rebuild runs, and one the old graph raises between the rebuild and the hand over.
 *
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 */
internal class Media3GraphLoads(
  private val scope: CoroutineScope,
) {
  private var issued = 0
  private var handedOver = 0

  /**
   * Starts a load and answers its number, for [handOver]. Engine's dispatcher only.
   *
   * Nothing raised before this reaches the engine after it.
   */
  fun issue(): Int = ++issued

  /**
   * Marks everything raised from here on as coming from [load]. Player's thread only.
   */
  fun handOver(load: Int) {
    handedOver = load
  }

  /**
   * Hops [block] onto the engine's dispatcher, unless a load has been issued since the one the player's thread holds.
   * Player's thread only.
   */
  fun report(block: () -> Unit) {
    val load = handedOver
    scope.launch { if (load == issued) block() }
  }
}
