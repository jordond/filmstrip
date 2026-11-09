package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.LaterClipOverlayParityContractTest

/**
 * The later clip overlay contract on AVFoundation, where the compositor request carries the composition time an
 * overlay reads.
 */
class AppleLaterClipOverlayParityHardwareTest : LaterClipOverlayParityContractTest(AppleClipParity) {
  init {
    pumpMainRunLoopDuringContracts()
  }
}
