// The Apple half of runner-probe.sh. Compiles a fixed Metal shader from source and encodes a fixed H.264 clip, and
// prints the milliseconds each took as "metal=<ms> encode=<ms>". Neither runs filmstrip code. A step that cannot run
// prints null for its figure.
import AVFoundation
import CoreVideo
import Foundation
import Metal

func milliseconds(_ work: () -> Bool) -> String {
  let start = DispatchTime.now().uptimeNanoseconds
  let done = work()
  let elapsed = (DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
  return done ? String(elapsed) : "null"
}

let shader = """
#include <metal_stdlib>
using namespace metal;
struct Vertex { float4 position [[position]]; float2 uv; };
fragment float4 probe(Vertex in [[stage_in]], texture2d<float> source [[texture(0)]]) {
  constexpr sampler linear(filter::linear);
  float4 sum = float4(0.0);
  for (int y = -4; y <= 4; y++) {
    for (int x = -4; x <= 4; x++) {
      sum += source.sample(linear, in.uv + float2(x, y) / 512.0) * exp(-float(x * x + y * y) / 18.0);
    }
  }
  return float4(pow(sum.rgb / 28.0, float3(1.0 / 2.2)), SALT);
}
"""
  // A constant no earlier run compiled, so the system shader cache never answers for the compiler.
  .replacingOccurrences(of: "SALT", with: "\(1.0 + Double(DispatchTime.now().uptimeNanoseconds % 1_000_000) / 1e9)")

let metal = milliseconds {
  guard let device = MTLCreateSystemDefaultDevice() else { return false }
  return (try? device.makeLibrary(source: shader, options: nil)) != nil
}

let encode = milliseconds {
  let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("runner-probe-\(getpid()).mp4")
  defer { try? FileManager.default.removeItem(at: url) }
  let width = 1280, height = 720, frames = 120
  guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return false }
  let input = AVAssetWriterInput(
    mediaType: .video,
    outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height]
  )
  let adaptor = AVAssetWriterInputPixelBufferAdaptor(
    assetWriterInput: input,
    sourcePixelBufferAttributes: [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
      kCVPixelBufferWidthKey as String: width,
      kCVPixelBufferHeightKey as String: height,
    ]
  )
  writer.add(input)
  guard writer.startWriting() else { return false }
  writer.startSession(atSourceTime: .zero)
  for frame in 0..<frames {
    while !input.isReadyForMoreMediaData {
      if writer.status != .writing { return false }
      usleep(1000)
    }
    var buffer: CVPixelBuffer?
    guard let pool = adaptor.pixelBufferPool,
      CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer) == kCVReturnSuccess, let pixels = buffer
    else { return false }
    CVPixelBufferLockBaseAddress(pixels, [])
    if let base = CVPixelBufferGetBaseAddress(pixels) {
      // A moving gradient, so the encoder has something to do on every frame.
      let row = CVPixelBufferGetBytesPerRow(pixels)
      let bytes = base.assumingMemoryBound(to: UInt8.self)
      for y in 0..<height {
        for x in stride(from: 0, to: width * 4, by: 4) {
          bytes[y * row + x] = UInt8(truncatingIfNeeded: x / 4 + frame * 3)
          bytes[y * row + x + 1] = UInt8(truncatingIfNeeded: y + frame * 2)
          bytes[y * row + x + 2] = UInt8(truncatingIfNeeded: frame * 5)
          bytes[y * row + x + 3] = 255
        }
      }
    }
    CVPixelBufferUnlockBaseAddress(pixels, [])
    if !adaptor.append(pixels, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: 30)) { return false }
  }
  input.markAsFinished()
  let finished = DispatchSemaphore(value: 0)
  writer.finishWriting { finished.signal() }
  finished.wait()
  return writer.status == .completed
}

print("metal=\(metal) encode=\(encode)")
