package dev.jordond.filmstrip.test

import kotlin.test.fail

/**
 * Fails unless [actual] matches [expected] closely enough to be the same frame.
 *
 * @throws AssertionError When PSNR or SSIM falls below its threshold.
 * @throws IllegalArgumentException When the two frames differ in size.
 */
public fun assertFramesSimilar(
  expected: TestFrame,
  actual: TestFrame,
  minPsnrDb: Double = DEFAULT_MIN_PSNR_DB,
  minSsim: Double = DEFAULT_MIN_SSIM,
  message: String? = null,
) {
  val comparison = compareFrames(expected, actual)
  if (comparison.psnrDb >= minPsnrDb && comparison.ssim >= minSsim) return

  fail(
    buildString {
      if (message != null) {
        append(message)
        append(": ")
      }
      append("frames differ beyond tolerance. ")
      append(comparison)
      append(" (needed PSNR >= $minPsnrDb dB and SSIM >= $minSsim).")
    },
  )
}

/**
 * Fails unless [actual] differs from [expected] by more than the tolerances.
 *
 * @throws AssertionError When both PSNR and SSIM are at or above their thresholds.
 * @throws IllegalArgumentException When the two frames differ in size.
 */
public fun assertFramesDiffer(
  expected: TestFrame,
  actual: TestFrame,
  minPsnrDb: Double = DEFAULT_MIN_PSNR_DB,
  minSsim: Double = DEFAULT_MIN_SSIM,
  message: String? = null,
) {
  val comparison = compareFrames(expected, actual)
  if (comparison.psnrDb < minPsnrDb || comparison.ssim < minSsim) return

  fail(
    buildString {
      if (message != null) {
        append(message)
        append(": ")
      }
      append("frames were expected to differ but matched. ")
      append(comparison)
    },
  )
}

/**
 * PSNR at or above which two frames are treated as the same, in decibels.
 */
public const val DEFAULT_MIN_PSNR_DB: Double = 40.0

/**
 * SSIM at or above which two frames are treated as the same.
 */
public const val DEFAULT_MIN_SSIM: Double = 0.995
