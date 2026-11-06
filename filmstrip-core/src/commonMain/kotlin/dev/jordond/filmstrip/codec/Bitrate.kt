package dev.jordond.filmstrip.codec

import dev.drewhamilton.poko.Poko
import kotlin.math.absoluteValue

/**
 * A bitrate.
 *
 * @property bitsPerSecond The rate, in bits per second.
 */
@Poko
public class Bitrate(
  public val bitsPerSecond: Long,
) {
  /**
   * Prints the rate in the largest unit it reaches, as in `2.5 Mbps`, `800 kbps` or `500 bps`.
   *
   * Megabits and kilobits keep at most two decimals, rounded half up with trailing zeros dropped. A rate that rounds
   * up to the next unit reads in that unit, so 999,999 bits per second prints `1 Mbps` and not `1000 kbps`.
   * [Long.MIN_VALUE] prints in bits per second, since its absolute value overflows.
   *
   * @return The rate as a label for a person to read.
   */
  override fun toString(): String {
    val magnitude = bitsPerSecond.absoluteValue
    return when {
      magnitude >= MBPS_FROM -> "${decimal(BITS_PER_MEGABIT)} Mbps"
      magnitude >= BITS_PER_KILOBIT -> "${decimal(BITS_PER_KILOBIT)} kbps"
      else -> "$bitsPerSecond bps"
    }
  }

  // Rounds off the remainder, since adding half a step first would overflow near Long.MAX_VALUE.
  private fun decimal(unit: Long): String {
    val step = unit / HUNDREDTHS
    val magnitude = bitsPerSecond.absoluteValue
    val hundredths = magnitude / step + if (magnitude % step >= step / 2) 1 else 0
    val sign = if (bitsPerSecond < 0) "-" else ""
    val whole = hundredths / HUNDREDTHS
    val fraction = (hundredths % HUNDREDTHS).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
  }

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

private const val BITS_PER_KILOBIT = 1_000L
private const val BITS_PER_MEGABIT = 1_000_000L
private const val HUNDREDTHS = 100L

// The lowest rate that rounds to 1000 kbps, so it reads as 1 Mbps.
private const val MBPS_FROM = 999_995L

/**
 * This many megabits per second.
 */
public val Int.Mbps: Bitrate get() = Bitrate.mbps(this)

/**
 * This many kilobits per second.
 */
public val Int.Kbps: Bitrate get() = Bitrate.kbps(this)
