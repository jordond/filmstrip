package dev.jordond.filmstrip.codec

import dev.drewhamilton.poko.Poko

/**
 * A bitrate.
 *
 * @property bitsPerSecond The rate, in bits per second.
 */
@Poko
public class Bitrate(
  public val bitsPerSecond: Long,
) {
  public companion object {
    /**
     * Creates a bitrate of [value] megabits per second.
     */
    public fun mbps(value: Double): Bitrate = Bitrate((value * 1_000_000).toLong())

    /**
     * Creates a bitrate of [value] megabits per second.
     */
    public fun mbps(value: Int): Bitrate = mbps(value.toDouble())

    /**
     * Creates a bitrate of [value] kilobits per second.
     */
    public fun kbps(value: Double): Bitrate = Bitrate((value * 1_000).toLong())

    /**
     * Creates a bitrate of [value] kilobits per second.
     */
    public fun kbps(value: Int): Bitrate = kbps(value.toDouble())
  }
}

/**
 * This many megabits per second.
 */
public val Int.Mbps: Bitrate get() = Bitrate.mbps(this)

/**
 * This many kilobits per second.
 */
public val Int.Kbps: Bitrate get() = Bitrate.kbps(this)
