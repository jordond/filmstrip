package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.tape.TapeStock
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.geometry.Fill

/**
 * How the frame is reframed, since [Crop] and [CropRect] are two different effects rather than two
 * settings of one.
 */
public enum class CropMode {
  Off,
  Aspect,
  Rect,
}

/**
 * Which tracking band the tape look runs, events now and then, one rolling band, or none.
 */
public enum class TapeTrackingMode {
  Events,
  Rolling,
  Off,
}

/**
 * A tape format the tape look and the tape audio can play.
 *
 * @property label The name the format picker shows.
 * @property stocks The speeds the format ran at, each under the name its chip shows.
 * @property default The speed the format starts on.
 */
public enum class TapeFormat(
  public val label: String,
  public val stocks: List<Pair<String, TapeStock>>,
  public val default: TapeStock,
) {
  Vhs("VHS", TapeSpeed.entries.map { it.name to it }, TapeSpeed.EP),
  Betamax("Betamax", BetaSpeed.entries.map { it.name to it }, BetaSpeed.II),
  Hi8("Hi8", Hi8Speed.entries.map { it.name to it }, Hi8Speed.SP),
  ;

  public companion object {
    /**
     * The format [stock] is a speed of.
     */
    public fun of(stock: TapeStock): TapeFormat = entries.first { format -> format.stocks.any { it.second == stock } }
  }
}

/**
 * Which arm of [Fill] backs the frame.
 */
public enum class FillMode {
  Solid,
  Blurred,
}

/**
 * Which arm of [OutputFrame] sets the frame the clip lands in.
 */
public enum class FrameMode {
  Auto,
  Aspect,
  Fixed,
}

/**
 * Which arm of [AudioSpec] the composition's mixed audio takes.
 */
public enum class AudioMode {
  Keep,
  Volume,
  Mute,
  Remove,
  AudioOnly,
}

/**
 * What the timeline strip lays out, the whole edit or the selected clip's own source.
 */
public enum class TimelineView {
  Sequence,
  Clip,
}

/**
 * Which end of a pan a rectangle on the stage edits.
 */
public enum class PanEnd {
  Start,
  End,
}

/**
 * What the editor is currently editing, which decides what the tool panel shows.
 */
public enum class EditorTool {
  Clip,
  Crop,
  Transform,
  Scale,
  Adjust,
  Text,
  Watermark,
  Stories,
  Audio,
  Tracks,
  Background,
}
