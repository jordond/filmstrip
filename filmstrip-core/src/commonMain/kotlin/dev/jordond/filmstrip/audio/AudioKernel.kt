package dev.jordond.filmstrip.audio

/**
 * The signal processing of one audio effect, written once and hosted by every backend that can run Kotlin.
 *
 * A kernel belongs to one stream of one pipeline and may keep state between calls. The host calls [process] with
 * consecutive blocks of that stream, and [reset] whenever the next block does not follow the last, such as after a
 * seek. Both run on an audio thread, so neither should allocate or block.
 *
 * The sample rate and channel count are the ones the kernel was built for, and they hold for its whole life.
 */
public abstract class AudioKernel {
  /**
   * How many frames this kernel delays its output by.
   *
   * Defaults to zero. The host drops this many frames from the head of the stream and feeds silence at the tail to
   * collect the rest, so the stream keeps its length and stays aligned with the picture. Read once, before the first
   * block.
   */
  public open val latencyFrames: Int
    get() = 0

  /**
   * Processes one block in place.
   *
   * @param buffer Interleaved samples, nominally from -1 to 1. The block is the first [frames] times the channel count
   * entries, and anything past that is not part of it.
   * @param frames How many frames the block holds. It varies from call to call.
   * @param position The index of the block's first frame on the output timeline, counted at the stream's sample rate
   * from the start of the composition. The same on every backend, in a preview and in an export.
   */
  public abstract fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  )

  /**
   * Clears whatever the kernel carries from one block to the next.
   *
   * The default does nothing, which suits a kernel with no state.
   *
   * @param position The timeline index of the frame the next block starts at.
   */
  public open fun reset(position: Long) {}
}
