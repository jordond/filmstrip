package dev.jordond.filmstrip.color

/**
 * The chroma sample layouts, numbered the way HEVC's `chroma_format_idc` is. An AV1 layout is given the HEVC number
 * for the same subsampling.
 */
internal object ChromaFormat {
  const val MONOCHROME = 0
  const val YUV420 = 1
  const val YUV422 = 2
  const val YUV444 = 3
}
