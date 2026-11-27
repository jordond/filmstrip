package dev.jordond.filmstrip.sample.stories

import kotlinx.browser.window

internal actual fun inputMode(): InputMode =
  if (window.matchMedia("(hover: hover) and (pointer: fine)").matches) InputMode.Pointer else InputMode.Touch
