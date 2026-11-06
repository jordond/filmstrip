package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.filekit.release
import dev.jordond.filmstrip.filekit.toMediaSink
import dev.jordond.filmstrip.filekit.toMediaSource
import io.github.vinceglb.filekit.PlatformFile

internal suspend fun fileKitSources(
  filmstrip: Filmstrip,
  picked: PlatformFile,
  saveTo: PlatformFile,
) {
  // region filekit
  val source = picked.toMediaSource()
  val composition = compositionOf { clip(source) }

  filmstrip.export(composition, ExportSpec.Upload, saveTo.toMediaSink()).collect { status ->
    println(status)
  }

  source.release()
  // endregion
}
