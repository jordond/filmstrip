package dev.jordond.filmstrip

public actual class PlatformContext private constructor() {
  public companion object {
    public val shared: PlatformContext = PlatformContext()
  }
}

internal actual fun platformContext(): PlatformContext = PlatformContext.shared
