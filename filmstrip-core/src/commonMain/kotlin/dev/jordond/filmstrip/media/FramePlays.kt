package dev.jordond.filmstrip.media

import dev.drewhamilton.poko.Poko

/**
 * How many times a sequence of frames plays through before it holds its last frame.
 */
public sealed interface FramePlays {
  /**
   * The sequence starts over every time it reaches its end and never holds.
   */
  public data object Forever : FramePlays

  /**
   * The sequence plays [count] times in a row, then holds its last frame.
   *
   * @property count The number of whole plays, at least one.
   * @throws IllegalArgumentException when [count] is below one.
   */
  @Poko
  public class Times(
    public val count: Int,
  ) : FramePlays {
    init {
      require(count >= 1) { "A sequence of frames plays at least once, and $count plays is not one." }
    }
  }

  public companion object {
    /**
     * The sequence plays through once, then holds its last frame.
     */
    public val Once: FramePlays = Times(1)
  }
}
