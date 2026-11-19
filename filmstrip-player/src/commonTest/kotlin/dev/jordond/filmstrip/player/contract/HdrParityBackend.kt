package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.internal.HdrPreviewReadback
import dev.jordond.filmstrip.test.encodesHdr
import kotlin.time.Duration

/**
 * What a backend hands the suites that hold a kept HDR grade's preview to its export: an HDR fixture per transfer, the
 * engine's HDR read and the codes an export writes.
 */
internal interface HdrParityBackend : ClipParityBackend {
  /**
   * This backend's HDR fixture clip in [transfer], which sets a kept grade wherever it sits in a composition.
   */
  suspend fun hdrFixture(transfer: HdrTransfer): MediaSource

  /**
   * The HDR read of [engine], which this backend built through [createEngine].
   */
  fun hdrReadback(engine: PlayerEngine): HdrPreviewReadback

  /**
   * The ten-bit luma, Cb and Cr codes [composition] exports at [position], read at [x] and [y] as fractions of the
   * frame's width and height.
   *
   * The export keeps the grade [composition]'s HDR clip sets.
   */
  suspend fun exportHdrCodes(
    composition: Composition,
    position: Duration,
    x: Double,
    y: Double,
  ): IntArray

  /**
   * Whether this device has an HDR encoder, without which no export keeps a grade to compare against.
   */
  suspend fun encodesHdr(): Boolean = filmstrip.encodesHdr()

  /**
   * How many codes per channel a preview read may sit from the export or the shared codes, loosened only for what this
   * backend's path costs.
   */
  val hdrCodeTolerance: Int get() = HDR_CODE_TOLERANCE
}

/**
 * What an encode leaves on a flat ten-bit patch, the tolerance every backend's HDR solid export reads use.
 */
internal const val HDR_CODE_TOLERANCE = 4
