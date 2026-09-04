package dev.jordond.filmstrip.webcodecs.internal

/**
 * Walks packets in decode order and hands [write] the ones a window ending at [endSeconds] needs,
 * in the order a muxer takes them.
 *
 * Decode order is not presentation order once a stream carries B frames, so a packet past
 * [endSeconds] can still be a reference for one before it. Such a packet is held rather than
 * written or dropped, and only written once a later packet turns out to be inside the window, which
 * is what leaves every frame the trim asked for decodable. The run still held when the walk ends
 * presents entirely past the cut and is dropped.
 *
 * The walk itself ends on the first key packet at or past [endSeconds]. A key packet opens a group
 * nothing before it presents into, so there is nothing after it this copy can still need.
 *
 * Generic over the packet so the rule can be driven by a plain sequence of timestamps rather than
 * only by a demuxer.
 *
 * @param next The next packet in decode order, or null once the walk has run out.
 * @param timestampOf When a packet presents, in seconds.
 * @param isKeyPacket Whether a packet is a sync sample.
 */
internal suspend fun <T> copyWindow(
  endSeconds: Double,
  next: suspend () -> T?,
  timestampOf: (T) -> Double,
  isKeyPacket: (T) -> Boolean,
  write: suspend (T) -> Unit,
) {
  val held = mutableListOf<T>()
  while (true) {
    val packet = next() ?: break
    if (timestampOf(packet) >= endSeconds) {
      if (isKeyPacket(packet)) break
      held += packet
      continue
    }
    held.forEach { write(it) }
    held.clear()
    write(packet)
  }
}
