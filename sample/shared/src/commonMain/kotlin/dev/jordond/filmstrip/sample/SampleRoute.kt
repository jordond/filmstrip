package dev.jordond.filmstrip.sample

import androidx.navigation3.runtime.NavKey

/**
 * Everywhere the sample can be.
 *
 * The keys carry no data because the session has exactly one clip in it, which lives in
 * [SampleAppState]. A route holding an id is the shape to copy for an app with a library of them.
 */
public sealed interface SampleRoute : NavKey {
  /**
   * The root. It holds the clip picker until a clip is imported, then the editor over it.
   */
  public data object Editor : SampleRoute

  /**
   * The export spec, the device's verdict on it, and the run.
   */
  public data object Export : SampleRoute

  /**
   * What this device's encoders say they can do, for reading mid edit. The root shows the same
   * report inline while nothing is loaded.
   */
  public data object Capabilities : SampleRoute

  /**
   * The file an export wrote.
   */
  public data object Result : SampleRoute

  /**
   * Everything this session knows about itself, shaped for a bug report.
   */
  public data object Diagnostics : SampleRoute
}
