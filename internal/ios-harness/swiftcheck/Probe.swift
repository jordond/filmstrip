import Foundation
import CoreImage
import CoreGraphics
import IosHarness

// Candidate A: the raw CGImageRef. Exported as `void *`, so Swift sees UnsafeMutableRawPointer.
func candidateA(_ image: HandoffImage) -> CGImage? {
    guard let raw = image.cgImage() else { return nil }
    // The only route back to a typed CGImage is an unchecked reinterpret.
    return Unmanaged<CGImage>.fromOpaque(raw).takeUnretainedValue()
}

// Candidate B: the CIImage control arm.
func candidateB(_ image: HandoffImage) -> CIImage? {
    return image.ciImage()
}

// Candidate C: the ByteArray copy escape.
func candidateC(_ image: HandoffImage) -> Int {
    let bytes = image.toRgba8888()
    return Int(bytes.size)
}

// Can Swift get the bytes out in bulk, or only one at a time?
func candidateCBulk(_ image: HandoffImage) -> Data {
    let bytes = image.toRgba8888()
    var out = Data(capacity: Int(bytes.size))
    for i in 0..<bytes.size {
        out.append(UInt8(bitPattern: bytes.get(index: i)))
    }
    return out
}

// The sealed result, matched the way a non-SKIE consumer must.
func matchResult(_ result: HandoffResult) -> String {
    if let success = result as? HandoffResultSuccess {
        return "ok \(success.image.widthPx)x\(success.image.heightPx) @ \(success.presentationTimeMillis)"
    } else if let failure = result as? HandoffResultFailure {
        return "fail \(failure.message)"
    }
    return "unreachable"
}
