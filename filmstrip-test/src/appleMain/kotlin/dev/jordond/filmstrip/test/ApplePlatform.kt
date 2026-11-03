package dev.jordond.filmstrip.test

import kotlin.experimental.ExperimentalNativeApi

/**
 * The Apple platforms a still's decode is measured or unmeasured on. [Iphone] is any iOS hardware, an iPad included.
 *
 * @property label How a failure names the platform.
 */
internal enum class ApplePlatform(
  val label: String,
) {
  MacOs("macOS"),
  Simulator("the iOS simulator"),
  Iphone("iOS hardware"),
  ;

  companion object {
    /**
     * The platform this process runs on.
     */
    @OptIn(ExperimentalNativeApi::class)
    fun current(): ApplePlatform =
      when {
        Platform.osFamily == OsFamily.MACOSX -> MacOs
        isSimulator() -> Simulator
        else -> Iphone
      }
  }
}
