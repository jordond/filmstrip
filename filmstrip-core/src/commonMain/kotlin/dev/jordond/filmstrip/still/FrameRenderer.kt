package dev.jordond.filmstrip.still

import androidx.compose.runtime.Stable
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.FrameResult
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Renders frames of an [EditComposition] with its effects applied.
 *
 * The part of [dev.jordond.filmstrip.Filmstrip] that draws pictures. A poster frame or a timeline
 * strip takes one of these, and a [dev.jordond.filmstrip.Filmstrip] is one.
 */
@Stable
public interface FrameRenderer {
  /**
   * Renders one frame of a composition, with its effects applied.
   *
   * Lands on the frame covering [at], rather than on the nearest sync sample the way [frames] may.
   *
   * @param composition The edit to render from.
   * @param at Where in the composition to render.
   * @param heightPx The height to render at, in pixels. Null renders at the composition's own output
   *   height, and so does a height at or above it. Zero or less fails with
   *   [dev.jordond.filmstrip.FilmstripError.InvalidComposition].
   * @param exportSpec The export this frame stands in for, which renders it at the HDR grade that export resolves to.
   * @return The frame, which the caller owns and must close, or why it could not be produced.
   */
  public suspend fun frame(
    composition: EditComposition,
    at: Duration,
    heightPx: Int? = null,
    exportSpec: ExportSpec = ExportSpec(),
  ): FrameResult

  /**
   * Renders several frames, emitting each as it is ready.
   *
   * For a timeline strip, which reads as a run of frames rather than as a set of exact instants.
   * Each frame may therefore come from the nearest sync sample rather than the one covering its
   * entry in [at], where that is the faster read. [FrameResult.Success.presentationTime] says where
   * a frame actually landed. Use [frame] when a position has to be exact.
   *
   * @param composition The edit to render from.
   * @param at Where in the composition to render each frame.
   * @param heightPx The height to render at, in pixels. Null renders at the composition's own output
   *   height, and so does a height at or above it. Zero or less fails with
   *   [dev.jordond.filmstrip.FilmstripError.InvalidComposition].
   * @param exportSpec The export this frame stands in for, which renders it at the HDR grade that export resolves to.
   * @return A flow of frames, one per entry in [at]. The caller owns each and must close it.
   */
  public fun frames(
    composition: EditComposition,
    at: List<Duration>,
    heightPx: Int? = null,
    exportSpec: ExportSpec = ExportSpec(),
  ): Flow<FrameResult>
}
