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

    /**
     * A player reached [dev.jordond.filmstrip.player.PlaybackStatus.Error], or an export finished with
     * [dev.jordond.filmstrip.export.ExportStatus.Failure].
     *
     * Emitted once per failure. A player that stays on the same error does not emit it again, and one that recovers
     * and fails again does. Always carries [Detail.STAGE], [Detail.ERROR], [Detail.MESSAGE] and [Detail.SOURCES].
     * Every other key on [Detail] that names a platform's own error is there when that platform reported it, and
     * left out when it did not. [source] is the backend that failed, or `filmstrip` when no backend was reached.
     *
     * Nothing in the payload names a file or a URI. Sources are reduced to a hash and an extension, and any path or
     * URI inside a message is rewritten the same way.
     */
    public const val FAILURE: String = "failure"
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

    /**
     * What failed in a [FAILURE], `playback` or `export`.
     */
    public const val STAGE: String = "stage"

    /**
     * The name of the error arm a [FAILURE] reported, such as `DecoderUnavailable`.
     *
     * An export that failed for more than one reason lists every arm, comma separated, in the order of
     * [dev.jordond.filmstrip.export.ExportStatus.Failure.reasons].
     */
    public const val ERROR: String = "error"

    /**
     * The message of the error a [FAILURE] reported, with any path or URI in it redacted.
     *
     * One line per reason when an export failed for more than one.
     */
    public const val MESSAGE: String = "message"

    /**
     * Every source in the failed composition, in track then clip order, comma separated.
     *
     * Each is reduced to `clip-<hash>.<extension>`, or to `bytes-<size>`, `image-<name>` or `solid` for a source
     * that is not a file.
     */
    public const val SOURCES: String = "sources"

    /**
     * The platform's numeric code for a [FAILURE].
     *
     * The media3 `errorCode` on Android, the `NSError` code on Apple and the process exit code for ffmpeg. A browser
     * gives no numeric code, so WebCodecs leaves it out.
     */
    public const val ERROR_CODE: String = "errorCode"

    /**
     * The name of the platform's code, such as media3's `ERROR_CODE_DECODING_FAILED` or a browser's
     * `EncodingError`. In a browser it is the name of the first `DOMException` in the error's cause chain.
     */
    public const val ERROR_NAME: String = "errorName"

    /**
     * The `NSError` domain of an Apple [FAILURE].
     */
    public const val ERROR_DOMAIN: String = "errorDomain"

    /**
     * The codec component that was running when a [FAILURE] happened, such as `c2.goldfish.h264.decoder`.
     *
     * Only media3 names one.
     */
    public const val CODEC_NAME: String = "codecName"

    /**
     * Whether the failing codec was a `decoder` or an `encoder`.
     */
    public const val CODEC_ROLE: String = "codecRole"

    /**
     * The format the failing codec was configured with, as the platform prints it.
     *
     * media3 prints the whole format. A browser names the codec string the decoder or encoder was opened with, such as
     * `avc1.64001f`.
     */
    public const val CODEC_FORMAT: String = "codecFormat"

    /**
     * The sample MIME type of the input that was being decoded, such as `video/avc`.
     */
    public const val INPUT_MIME: String = "inputMime"

    /**
     * The RFC 6381 codecs string of the input that was being decoded, such as `avc1.64001F`.
     */
    public const val INPUT_CODECS: String = "inputCodecs"

    /**
     * The size of the input that was being decoded, as `<width>x<height>`.
     */
    public const val INPUT_SIZE: String = "inputSize"

    /**
     * The frame rate of the input that was being decoded, in frames per second.
     */
    public const val INPUT_FRAME_RATE: String = "inputFrameRate"

    /**
     * The chain of causes under a [FAILURE], one level per line, outermost first.
     *
     * A JVM exception reads as its class name then its message. A browser error reads as its name then its message,
     * following each `cause`. An Apple error reads as its domain and code then its description. ffmpeg has none,
     * since what it wrote to stderr is already the message. Paths and URIs are redacted.
     */
    public const val CAUSES: String = "causes"
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
