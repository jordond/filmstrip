package dev.jordond.filmstrip.testing

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlayerConfig
import kotlin.time.Duration

/**
 * One call a test's subject made on a [FakeFilmstrip], with the arguments it passed.
 *
 * The fake keeps them in the order they arrived, so a test asserts on what was asked for and when.
 */
public sealed interface FilmstripCall {
  /**
   * A call to `probe`.
   *
   * @property source The media that was asked about.
   */
  @Poko
  public class Probe(
    public val source: MediaSource,
  ) : FilmstripCall

  /**
   * A call to `capabilities`.
   */
  public data object Capabilities : FilmstripCall

  /**
   * A call to `plan`.
   *
   * @property composition The edit that was planned.
   * @property spec The output that was asked for.
   */
  @Poko
  public class Plan(
    public val composition: Composition,
    public val spec: ExportSpec,
  ) : FilmstripCall

  /**
   * A call to the `export` that runs a plan.
   *
   * @property plan The plan that was handed over.
   * @property sink Where the output was to be written.
   */
  @Poko
  public class ExportOfPlan(
    public val plan: ExportPlan,
    public val sink: MediaSink,
  ) : FilmstripCall

  /**
   * A call to the `export` that plans and exports in one go.
   *
   * @property composition The edit that was exported.
   * @property spec The output that was asked for.
   * @property sink Where the output was to be written.
   */
  @Poko
  public class ExportOfComposition(
    public val composition: Composition,
    public val spec: ExportSpec,
    public val sink: MediaSink,
  ) : FilmstripCall

  /**
   * A call to `parityOf`.
   *
   * @property specId The effect that was asked about.
   */
  @Poko
  public class ParityOf(
    public val specId: String,
  ) : FilmstripCall

  /**
   * A call to `frame`.
   *
   * @property composition The edit the frame was rendered from.
   * @property at Where in the composition the frame was asked for.
   * @property heightPx The height that was asked for, or null for the composition's own.
   * @property exportSpec The export the frame stood in for.
   */
  @Poko
  public class Frame(
    public val composition: Composition,
    public val at: Duration,
    public val heightPx: Int?,
    public val exportSpec: ExportSpec,
  ) : FilmstripCall

  /**
   * A call to `frames`.
   *
   * @property composition The edit the frames were rendered from.
   * @property at Where in the composition each frame was asked for.
   * @property heightPx The height that was asked for, or null for the composition's own.
   * @property exportSpec The export the frames stood in for.
   */
  @Poko
  public class Frames(
    public val composition: Composition,
    public val at: List<Duration>,
    public val heightPx: Int?,
    public val exportSpec: ExportSpec,
  ) : FilmstripCall

  /**
   * A call to `preview`.
   *
   * @property composition The edit the player was opened over.
   * @property config How the player was asked to behave.
   */
  @Poko
  public class Preview(
    public val composition: Composition,
    public val config: PlayerConfig,
  ) : FilmstripCall
}
