package dev.jordond.filmstrip.diagnostics

import dev.drewhamilton.poko.Poko

/**
 * Something a component learned while running that no return value carries.
 *
 * The ffmpeg version banner and the command line filmstrip spawned are both here: neither belongs
 * in a [dev.jordond.filmstrip.export.Verdict] or an [dev.jordond.filmstrip.export.ExportStatus],
 * and both are the first thing a bug report is asked for.
 *
 * @property source Which component emitted it, matching a [BackendInfo.name] where a backend did.
 * @property name A stable key for what the event is, one of the names on the companion such as
 *   [TOOLCHAIN] or [INVOCATION]. Switch on this rather than on anything in [detail].
 * @property detail The event's payload, keyed by the names on [Detail].
 */
@Poko
public class DiagnosticEvent(
  public val source: String,
  public val name: String,
  public val detail: Map<String, String>,
) {
  public companion object {
    /**
     * The binaries a backend resolved, reported the first time anything asks for them.
     *
     * Carries [Detail.FFMPEG], [Detail.FFPROBE], [Detail.VERSION] and [Detail.BANNER] when they were
     * found, and [Detail.FOUND] and [Detail.REASON] when they were not.
     */
    public const val TOOLCHAIN: String = "toolchain"

    /**
     * The command line an export spawned, in [Detail.COMMAND].
     *
     * Carries [Detail.REASON] as well when the run fell back on a slower form of the same graph.
     */
    public const val INVOCATION: String = "invocation"

    /**
     * The command line a preview stream spawned, in [Detail.COMMAND].
     *
     * Carries [Detail.REASON] as well when the stream fell back on a slower form of the same graph.
     */
    public const val PREVIEW: String = "preview"
  }

  /**
   * The keys a [detail] map is written with.
   */
  public object Detail {
    /**
     * A spawned command line, joined with spaces.
     */
    public const val COMMAND: String = "command"

    /**
     * The absolute path of the ffmpeg binary that was resolved.
     */
    public const val FFMPEG: String = "ffmpeg"

    /**
     * The absolute path of the ffprobe binary that was resolved.
     */
    public const val FFPROBE: String = "ffprobe"

    /**
     * The version the resolved binary printed.
     */
    public const val VERSION: String = "version"

    /**
     * The whole version banner, build configuration included.
     */
    public const val BANNER: String = "banner"

    /**
     * The version of a binary that was found and refused, or `nothing` when none was found.
     */
    public const val FOUND: String = "found"

    /**
     * Why a lookup failed or a run fell back, readable by a person.
     */
    public const val REASON: String = "reason"
  }
}

/**
 * Receives every [DiagnosticEvent] the components emit.
 *
 * Register one with `FilmstripBuilder.addDiagnosticListener`. Events arrive on whichever thread the
 * work is running on, so an implementation that touches shared state does its own synchronising.
 */
public fun interface DiagnosticListener {
  /**
   * Called once per event.
   */
  public fun onEvent(event: DiagnosticEvent)
}
