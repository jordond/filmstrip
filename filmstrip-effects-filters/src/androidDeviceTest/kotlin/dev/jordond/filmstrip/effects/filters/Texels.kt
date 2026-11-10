package dev.jordond.filmstrip.effects.filters

// A texel a test drew, as the doubles a look's signal reads.
internal fun FloatArray.toDoubles(): DoubleArray = DoubleArray(size) { this[it].toDouble() }
