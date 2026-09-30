package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Writes this file through [write] so that it only ever appears whole.
 *
 * The bytes go to a temporary file in the same directory, which is then renamed over this one. A run killed part way
 * through leaves at most a stray temporary file, never a truncated fixture that a later run would find and read.
 *
 * @throws IOException When the temporary file cannot be renamed over this one.
 */
@InternalFilmstripApi
public fun File.writeAtomically(write: (OutputStream) -> Unit) {
  val partial = File.createTempFile("$name.", ".partial", absoluteFile.parentFile)
  try {
    partial.outputStream().use(write)
    if (!partial.renameTo(this)) throw IOException("could not move $partial over $this")
  } finally {
    partial.delete()
  }
}
