package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.concurrent.Volatile

/**
 * Holds the longest texture side [read] gives, never below [floor].
 *
 * A read that succeeds is kept and never asked again. One that gives null, such as a graphics context the platform
 * would not hand out this time, answers [floor] and leaves the next call to read again. A read below [floor] is a
 * context breaking its graphics API's promise and is held to [floor].
 *
 * @param floor The fewest pixels on a side the graphics API promises any context's textures hold.
 * @param read The context's `MAX_TEXTURE_SIZE`, or null when it could not be read.
 */
@InternalFilmstripApi
public class TextureSideProbe(
  private val floor: Int,
  private val read: () -> Int?,
) {
  @Volatile
  private var known: Int? = null

  /**
   * The longest texture side, from the kept read or a new one.
   *
   * @return The side in pixels, never below the floor.
   */
  public fun side(): Int {
    known?.let { return it }
    val side = read()?.coerceAtLeast(floor) ?: return floor
    known = side
    return side
  }
}
