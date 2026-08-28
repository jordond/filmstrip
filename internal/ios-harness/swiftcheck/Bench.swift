import Foundation
import IosHarness

let frameBytes: Int32 = 1920 * 1080 * 4   // one 1080p RGBA_8888 frame
let reps = 5

// Every sum lands here and is printed, so no arm's loop can be optimised away. The 0.000 ms
// readings this replaces were dead-code elimination, not speed.
var sink: Int64 = 0

func best(_ body: () -> Int64) -> Double {
    var lowest = Double.greatestFiniteMagnitude
    for _ in 0..<reps {
        let start = DispatchTime.now().uptimeNanoseconds
        sink &+= body()
        let ms = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000.0
        lowest = min(lowest, ms)
    }
    return lowest
}

// Construction hoisted out of all three read arms, so only the crossing is timed.
let kotlinArray = BridgeProbe.shared.byteArray(sizeBytes: frameBytes)
let data = BridgeProbe.shared.nsData(sizeBytes: frameBytes) as Data
let native = [UInt8](repeating: 7, count: Int(frameBytes))

let msArray = best {
    var sum: Int64 = 0
    for i in 0..<kotlinArray.size { sum &+= Int64(kotlinArray.get(index: i)) }
    return sum
}
let msData = best {
    var sum: Int64 = 0
    data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in for b in raw { sum &+= Int64(b) } }
    return sum
}
let msNative = best {
    var sum: Int64 = 0
    native.withUnsafeBufferPointer { buf in for b in buf { sum &+= Int64(b) } }
    return sum
}
let msMakeArray = best { Int64(BridgeProbe.shared.byteArray(sizeBytes: frameBytes).size) }
let msMakeData  = best { Int64((BridgeProbe.shared.nsData(sizeBytes: frameBytes) as Data).count) }

print("arm\tms_per_1080p_frame")
print("read: KotlinByteArray.get(index:)\t\(String(format: "%.3f", msArray))")
print("read: NSData -> Data\t\(String(format: "%.3f", msData))")
print("read: native [UInt8] control\t\(String(format: "%.3f", msNative))")
print("make: ByteArray across the bridge\t\(String(format: "%.3f", msMakeArray))")
print("make: NSData across the bridge\t\(String(format: "%.3f", msMakeData))")
print("# checksum sink \(sink), printed so no loop can be eliminated")
