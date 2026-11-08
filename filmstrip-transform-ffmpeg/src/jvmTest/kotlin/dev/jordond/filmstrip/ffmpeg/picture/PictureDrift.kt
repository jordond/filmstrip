package dev.jordond.filmstrip.ffmpeg.picture

// How far a picture this ffmpeg decodes may sit from core's exact conversion, in eight-bit codes, through its integer
// YCbCr conversion and, past eight bits, its sixteen-bit shortfall. The picture tests read texels that a wrong matrix,
// range or plane order moves by eight codes or more. Across FfmpegPictureYcbcrMediaTest and
// FfmpegPictureUnreadCodestreamMediaTest the largest seen is 2 on ffmpeg 9.0.2, from a BT.709 limited AVIF.
internal const val PICTURE_DRIFT = 2

// How far an identity picture this ffmpeg decodes deeper than eight bits may sit from core's exact conversion, in
// eight-bit codes. ffmpeg hands its planes back as they are and core's evaluator for the identity reading turns them
// into RGB, the same one the expected texel comes from, so FfmpegPictureIdentityMediaTest sees 0 on ffmpeg 9.0.2.
internal const val IDENTITY_DRIFT = 0
