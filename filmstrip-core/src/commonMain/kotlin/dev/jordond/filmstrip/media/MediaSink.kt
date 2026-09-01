package dev.jordond.filmstrip.media

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.export.ExportStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines where a media file should be exported to.
 */
@Serializable
public sealed interface MediaSink {
  /**
   * A file on disk.
   *
   * @property path A filesystem path whose parent directory must exist and be writable.
   */
  @Serializable
  @SerialName("path")
  @Poko
  public class Path(
    public val path: String,
  ) : MediaSink

  /**
   * A platform-native destination.
   *
   * @property uri An `android.net.Uri` string on Android, or an `NSURL` absolute string on Apple platforms.
   */
  @Serializable
  @SerialName("uri")
  @Poko
  public class Uri(
    public val uri: String,
  ) : MediaSink

  /**
   * A library-chosen temporary location, reported back on [ExportStatus.Success.output] as a resolved [Path].
   *
   * The file belongs to the caller once the export finishes: filmstrip never deletes it.
   */
  @Serializable
  @SerialName("temporary")
  public data object Temporary : MediaSink

  public companion object {
    /**
     * A sink writing to [path].
     */
    public fun of(path: String): MediaSink = Path(path)

    /**
     * A sink writing to a platform URI or URL string.
     */
    public fun ofUri(uri: String): MediaSink = Uri(uri)

    /**
     * A sink writing to a library-chosen temporary file.
     */
    public fun temporary(): MediaSink = Temporary
  }
}
