package dev.jordond.filmstrip.effects.spatial

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.GRADE_WITH_ALPHA_TO_FLOAT
import dev.jordond.filmstrip.effects.HdrGradeTables
import dev.jordond.filmstrip.effects.LINEAR
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.lut1d
import dev.jordond.filmstrip.effects.shiftedSignalToGrade
import dev.jordond.filmstrip.effects.signalFromGrade
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * The nodes that carry a kept [transfer] grade onto float planes holding each channel's scaled SDR signal, and back,
 * with the frame's alpha on a plane of its own.
 *
 * The scaled SDR signal is the SDR signal a channel's light would have been at reference white, divided by the
 * transfer's ceiling so its peak reads as one. A blur or a multiply there is the same as one on the SDR signal, and
 * the tables on the way back clamp at one, which is the ceiling. Both ways read [HdrGradeTables] with no matrix, so
 * they are the tables a color effect on the same grade runs through.
 */
internal class SpatialSignal(
  transfer: HdrTransfer,
) {
  private val tables = HdrGradeTables(ColorMatrix(), transfer)
  private val read = mutableListOf<Sidecar>()

  /**
   * The nodes from the grade's codes to `gbrapf32le` planes of scaled SDR signal and alpha.
   */
  val opening: List<FilterNode> =
    buildList {
      addAll(GRADE_WITH_ALPHA_TO_FLOAT)
      addAll(read.signalFromGrade())
      tables.decodeWarp?.let { add(read.lut1d(it, LINEAR)) }
      add(read.lut1d(tables.curve, LINEAR))
    }

  /**
   * The nodes from [opening]'s planes back to the grade's codes, as `yuva420p10le` with the alpha the planes hold.
   */
  val closing: List<FilterNode> =
    buildList {
      add(read.lut1d(tables.encodeWarp, LINEAR))
      add(read.lut1d(tables.encode, LINEAR))
      addAll(read.shiftedSignalToGrade(alpha = true))
    }

  /**
   * Every table [opening] and [closing] read.
   */
  val sidecars: List<Sidecar> get() = read.distinct()
}
