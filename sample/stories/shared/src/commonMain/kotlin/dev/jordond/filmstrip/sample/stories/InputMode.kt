package dev.jordond.filmstrip.sample.stories

/**
 * How the editor is laid out: for fingers, with every tool floating over a full screen picture, or for a mouse and
 * keyboard, with a tool rail, an inspector and the key bindings in view.
 *
 * Both lay the same stage out and drive the same editor, so neither can do something the other cannot.
 */
internal enum class InputMode { Touch, Pointer }

/**
 * The layout this device starts in. A phone and a tablet are [InputMode.Touch], a desktop is [InputMode.Pointer], and
 * a browser is whichever its primary pointer is, read once.
 */
internal expect fun inputMode(): InputMode
