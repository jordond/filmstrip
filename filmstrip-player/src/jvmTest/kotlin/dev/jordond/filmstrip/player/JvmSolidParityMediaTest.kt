package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.SolidParityContractTest

/**
 * The solid parity contract on ffmpeg, where a solid is a generated colour input in the preview's graph and the
 * export's.
 */
class JvmSolidParityMediaTest : SolidParityContractTest(JvmClipParity)
