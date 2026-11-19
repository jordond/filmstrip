package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.SolidParityContractTest

/**
 * The solid parity contract on AVFoundation, where the chain draws a solid's colour over a generated seed in the
 * preview and the export.
 */
class AppleSolidParityHardwareTest : SolidParityContractTest(AppleClipParity) {
  init {
    pumpMainRunLoopDuringContracts()
  }
}
