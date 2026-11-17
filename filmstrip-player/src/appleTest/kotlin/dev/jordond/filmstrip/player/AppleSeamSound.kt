package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.HeardSound
import dev.jordond.filmstrip.player.contract.SeamSoundBackend
import dev.jordond.filmstrip.player.contract.SeamSoundContractTest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSURL
import kotlin.test.fail

/**
 * The seam sound contract on AVFoundation, which reads the file it wrote back through `AVAudioFile`.
 */
internal object AppleSeamSound : SeamSoundBackend, ClipParityBackend by AppleClipParity {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @OptIn(ExperimentalForeignApi::class)
  override suspend fun soundOf(composition: Composition): HeardSound {
    val path = AppleClipParity.writtenPath(composition)
    val file = AVAudioFile(forReading = NSURL.fileURLWithPath(path), error = null)
    val frames = file.length.toInt()
    val buffer = AVAudioPCMBuffer(pCMFormat = file.processingFormat, frameCapacity = frames.toUInt())
    if (!file.readIntoBuffer(buffer, error = null)) fail("reading the audio of $path failed")
    val channel = buffer.floatChannelData?.get(0) ?: fail("the audio of $path is not float")
    return HeardSound(FloatArray(buffer.frameLength.toInt()) { channel[it] }, file.processingFormat.sampleRate.toInt())
  }
}

/**
 * The seam sound cells on AVFoundation.
 */
class AppleSeamSoundHardwareTest : SeamSoundContractTest(AppleSeamSound)
