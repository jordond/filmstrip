package dev.jordond.filmstrip.player.internal

/**
 * Runs [block] inside a callback that AVFoundation or a notification centre made, and swallows anything it throws.
 *
 * An exception escaping into the Objective-C frame that called the block ends the process, and nothing on that frame
 * can report it. Every caller either re-reads its state on the next callback or completes its work on failure itself.
 *
 * @return true when [block] ran to its end, false when it threw.
 */
internal inline fun insideObjcFrame(block: () -> Unit): Boolean =
  try {
    block()
    true
  } catch (
    @Suppress("SwallowedException", "TooGenericExceptionCaught") broken: Exception,
  ) {
    false
  }
