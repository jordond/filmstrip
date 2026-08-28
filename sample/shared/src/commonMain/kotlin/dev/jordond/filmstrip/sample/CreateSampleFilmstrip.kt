package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.create

/**
 * Builds the sample's [Filmstrip] through the bundle artifact, which registers every backend.
 *
 * The preview backend it registers has not landed on Android yet, which is fine: this app is here
 * to exercise the encoder.
 */
fun createSampleFilmstrip(): Filmstrip = Filmstrip.create()
