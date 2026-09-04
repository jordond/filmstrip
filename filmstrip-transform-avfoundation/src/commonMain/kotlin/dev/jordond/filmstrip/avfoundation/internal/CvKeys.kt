package dev.jordond.filmstrip.avfoundation.internal

/**
 * The pixel buffer attribute names, written out rather than taken from CoreVideo.
 *
 * `kCVPixelBufferPixelFormatTypeKey` and its neighbours are `CFStringRef`, which is a pointer in
 * Kotlin and not an `NSString`. A Kotlin map holding one bridges into a dictionary whose keys are
 * that pointer, and AVFoundation then reads a dictionary carrying nothing it recognises. macOS
 * ignores it. iOS answers `AVFoundationErrorDomain -11800`, underlying OSStatus -17281, from
 * `startWriting`, which reads as the write failing rather than as the attributes being unreadable.
 *
 * Each literal is the documented value of the constant it names. A dictionary handed to a C
 * function rather than to an Objective-C one takes the real constant instead, since nothing is
 * bridged on that path.
 */
internal const val PIXEL_FORMAT_KEY = "PixelFormatType"

/**
 * The documented value of `kCVPixelBufferWidthKey`. See [PIXEL_FORMAT_KEY].
 */
internal const val PIXEL_BUFFER_WIDTH_KEY = "Width"

/**
 * The documented value of `kCVPixelBufferHeightKey`. See [PIXEL_FORMAT_KEY].
 */
internal const val PIXEL_BUFFER_HEIGHT_KEY = "Height"
