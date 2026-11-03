package dev.jordond.filmstrip.webcodecs.internal.render

// The WebGL2 enums every pass here passes to the context, spelled once for all of them.

internal const val GL_ARRAY_BUFFER: Int = 0x8892
internal const val GL_BLEND: Int = 0x0BE2
internal const val GL_BROWSER_DEFAULT_WEBGL: Int = 0x9244
internal const val GL_CLAMP_TO_EDGE: Int = 0x812F
internal const val GL_COLOR_ATTACHMENT0: Int = 0x8CE0
internal const val GL_COLOR_BUFFER_BIT: Int = 0x4000
internal const val GL_COMPILE_STATUS: Int = 0x8B81
internal const val GL_FLOAT: Int = 0x1406
internal const val GL_FRAGMENT_SHADER: Int = 0x8B30
internal const val GL_FRAMEBUFFER: Int = 0x8D40
internal const val GL_FRAMEBUFFER_COMPLETE: Int = 0x8CD5
internal const val GL_HALF_FLOAT: Int = 0x140B
internal const val GL_LINEAR: Int = 0x2601
internal const val GL_LINK_STATUS: Int = 0x8B82
internal const val GL_MAX_TEXTURE_SIZE: Int = 0x0D33
internal const val GL_NEAREST: Int = 0x2600
internal const val GL_NONE: Int = 0
internal const val GL_ONE: Int = 1
internal const val GL_ONE_MINUS_SRC_ALPHA: Int = 0x0303
internal const val GL_R16UI: Int = 0x8234
internal const val GL_RED_INTEGER: Int = 0x8D94
internal const val GL_RGBA: Int = 0x1908
internal const val GL_RGBA16F: Int = 0x881A
internal const val GL_SRC_ALPHA: Int = 0x0302
internal const val GL_STATIC_DRAW: Int = 0x88E4
internal const val GL_TEXTURE0: Int = 0x84C0
internal const val GL_TEXTURE_2D: Int = 0x0DE1
internal const val GL_TEXTURE_MAG_FILTER: Int = 0x2800
internal const val GL_TEXTURE_MIN_FILTER: Int = 0x2801
internal const val GL_TEXTURE_WRAP_S: Int = 0x2802
internal const val GL_TEXTURE_WRAP_T: Int = 0x2803
internal const val GL_TRIANGLE_STRIP: Int = 0x0005
internal const val GL_UNPACK_ALIGNMENT: Int = 0x0CF5
internal const val GL_UNPACK_COLORSPACE_CONVERSION_WEBGL: Int = 0x9243
internal const val GL_UNPACK_FLIP_Y_WEBGL: Int = 0x9240
internal const val GL_UNPACK_PREMULTIPLY_ALPHA_WEBGL: Int = 0x9241
internal const val GL_UNPACK_ROW_LENGTH: Int = 0x0CF2
internal const val GL_UNSIGNED_BYTE: Int = 0x1401
internal const val GL_UNSIGNED_SHORT: Int = 0x1403
internal const val GL_VERTEX_SHADER: Int = 0x8B31

/**
 * How many vertices the unit quad every pass draws as a triangle strip carries.
 */
internal const val QUAD_VERTICES: Int = 4
