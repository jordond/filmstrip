package dev.jordond.filmstrip.media

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Duration

/**
 * Something to read media from.
 *
 * Construct one through the companion factories, which are also what a Swift caller uses.
 */
@Serializable
public sealed interface MediaSource {
  /**
   * A file on disk.
   *
   * @property path A filesystem path, readable by the calling process.
   */
  @Serializable
  @SerialName("path")
  @Poko
  public class Path(
    public val path: String,
  ) : MediaSource

  /**
   * A platform-native content reference.
   *
   * @property uri An `android.net.Uri` string on Android, such as `content://` or `file://`, or an
   *   `NSURL` absolute string on Apple platforms.
   */
  @Serializable
  @SerialName("uri")
  @Poko
  public class Uri(
    public val uri: String,
  ) : MediaSource

  /**
   * Bytes already in memory, for tests and small assets.
   *
   * Equality reads the whole of [bytes], so treat the array as owned by this source once you hand
   * it over. Writing into it afterwards is unsupported, because the hash is only computed once.
   *
   * @property bytes The encoded media, container and all.
   * @property hint What container [bytes] holds, or null to let the backend sniff it.
   */
  @Serializable
  @SerialName("bytes")
  @Poko
  public class Bytes(
    @Poko.ReadArrayContent public val bytes: ByteArray,
    public val hint: FormatHint? = null,
  ) : MediaSource {
    @Transient
    private var memoizedHash: Int = 0

    /**
     * Memoized, because a source is used as a map key on the probe path and [bytes] can be
     * hundreds of megabytes.
     */
    override fun hashCode(): Int {
      // A genuinely zero hash recomputes rather than reading the memo. Harmless, and it saves
      // carrying a second field to say whether the memo is populated.
      if (memoizedHash == 0) {
        memoizedHash = 31 * bytes.contentHashCode() + hint.hashCode()
      }
      return memoizedHash
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other == null || this::class != other::class) return false

      other as Bytes

      // hashCode() rather than the field, because the memo is empty until something asks for it
      // and two equal sources are rarely both populated.
      if (hashCode() != other.hashCode()) return false
      if (!bytes.contentEquals(other.bytes)) return false
      if (hint != other.hint) return false

      return true
    }
  }

  /**
   * A still image held on screen for a fixed length of time.
   *
   * A photo on a timeline, or a title card the host drew and handed over as bytes. Every frame it
   * contributes is the same pixels, so a trim over one keeps exactly the range it names.
   *
   * @property image Where to read the still from.
   * @property duration How long the still is held.
   */
  @Serializable
  @SerialName("image")
  @Poko
  @ExperimentalFilmstripApi
  public class Image(
    public val image: ImageSource,
    public val duration: Duration,
  ) : MediaSource

  public companion object {
    /**
     * A source reading from [path].
     */
    public fun of(path: String): MediaSource = Path(path)

    /**
     * A source reading from a platform URI or URL string.
     */
    public fun ofUri(uri: String): MediaSource = Uri(uri)

    /**
     * A source reading from [bytes], optionally with a container [hint].
     */
    public fun ofBytes(
      bytes: ByteArray,
      hint: FormatHint? = null,
    ): MediaSource = Bytes(bytes, hint)

    /**
     * A source holding [image] on screen for [duration].
     */
    @ExperimentalFilmstripApi
    public fun ofImage(
      image: ImageSource,
      duration: Duration,
    ): MediaSource = Image(image, duration)
  }
}

/**
 * A hint about what a byte buffer contains, so the backend can skip sniffing the container.
 *
 * Covers the containers both platforms read.
 */
@Serializable
public enum class FormatHint {
  Mp4,
  Mov,
  M4a,
  ThreeGp,
}

/**
 * Renders a source as a short string for an error message.
 */
@InternalFilmstripApi
public fun MediaSource.describe(): String =
  when (this) {
    is MediaSource.Path -> path
    is MediaSource.Uri -> uri
    is MediaSource.Bytes -> "bytes[${bytes.size}]"
    is MediaSource.Image -> image.describe()
  }
