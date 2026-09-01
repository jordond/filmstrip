package dev.jordond.filmstrip.compose.ui

/**
 * What a video stage falls back to.
 */
public object VideoStageDefaults {
  /**
   * How far from square a stage will letterbox itself.
   *
   * The stage's own limit, and the widest and tallest boxes it will lay out. An aspect outside it still draws its
   * picture at the aspect it really has, letterboxed inside a box of the nearest shape this allows, so a composition
   * reporting something degenerate costs a bar rather than a frame with no width.
   */
  public val AspectRange: ClosedFloatingPointRange<Float> = 0.2f..5f
}
