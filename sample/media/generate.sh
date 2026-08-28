#!/usr/bin/env bash
#
# Builds the sample's clip corpus into build/.
#
# Everything here is either generated from ffmpeg's own sources, which carries no licence at all,
# or cut from a film whose licence allows it. See README.md for what came from where.

set -euo pipefail

cd "$(dirname "$0")"
out="build"
mkdir -p "$out"

ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

echo "720p H.264, the ordinary case"
ff -f lavfi -t 10 -i testsrc2=size=1280x720:rate=30 -f lavfi -t 10 -i sine=frequency=440:sample_rate=48000 \
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/h264-720p-aac.mp4"

echo "720p H.264 with no audio track"
ff -f lavfi -t 5 -i testsrc2=size=1280x720:rate=30 \
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p \
  -movflags +faststart "$out/h264-720p-silent.mp4"

echo "1080p H.265"
ff -f lavfi -t 8 -i testsrc2=size=1920x1080:rate=30 -f lavfi -t 8 -i sine=frequency=330:sample_rate=48000 \
  -c:v libx265 -preset medium -crf 26 -pix_fmt yuv420p -tag:v hvc1 -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/hevc-1080p-aac.mp4"

echo "Portrait, no rotation in the metadata"
ff -f lavfi -t 6 -i testsrc2=size=720x1280:rate=30 -f lavfi -t 6 -i sine=frequency=440:sample_rate=48000 \
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/h264-portrait.mp4"

echo "Landscape frames with a 90 degree display matrix"
ff -f lavfi -t 6 -i testsrc2=size=1280x720:rate=30 -f lavfi -t 6 -i sine=frequency=440:sample_rate=48000 \
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/.rotated-source.mp4"
ff -display_rotation 90 -i "$out/.rotated-source.mp4" -c copy \
  -movflags +faststart "$out/h264-portrait-rotated.mp4"
rm -f "$out/.rotated-source.mp4"

echo "Audio that stops before the video does"
ff -f lavfi -t 8 -i testsrc2=size=1280x720:rate=30 -f lavfi -t 3 -i sine=frequency=440:sample_rate=48000 \
  -map 0:v -map 1:a -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/h264-short-audio.mp4"

echo "Mono at 44.1 kHz"
ff -f lavfi -t 5 -i testsrc2=size=1280x720:rate=30 -f lavfi -t 5 -i sine=frequency=440:sample_rate=44100 \
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 96k -ac 1 -ar 44100 \
  -movflags +faststart "$out/h264-mono-44100.mp4"

echo "Non-square pixels"
ff -f lavfi -t 5 -i testsrc2=size=720x480:rate=30 -f lavfi -t 5 -i sine=frequency=440:sample_rate=48000 \
  -vf setsar=40/33 -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/h264-anamorphic.mp4"

echo "4K60 with no B-frames"
ff -f lavfi -t 3 -i testsrc2=size=3840x2160:rate=60 -f lavfi -t 3 -i sine=frequency=440:sample_rate=48000 \
  -c:v libx264 -preset veryfast -crf 26 -bf 0 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/h264-4k60.mp4"

echo "8K H.265"
ff -f lavfi -t 3 -i testsrc2=size=7680x4320:rate=24 \
  -c:v hevc_videotoolbox -b:v 40M -pix_fmt yuv420p -tag:v hvc1 \
  -movflags +faststart "$out/hevc-8k24.mp4"

echo "240 fps"
ff -f lavfi -t 4 -i testsrc2=size=1280x720:rate=240 -f lavfi -t 4 -i sine=frequency=440:sample_rate=48000 \
  -c:v libx264 -preset veryfast -crf 26 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/h264-720p-240fps.mp4"

echo "Ten minutes of 180p"
ff -f lavfi -t 600 -i testsrc2=size=320x180:rate=30 -f lavfi -t 600 -i sine=frequency=220:sample_rate=48000 \
  -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 64k -ac 2 \
  -movflags +faststart "$out/h264-180p-10min.mp4"

echo "AVC and AAC in Matroska"
ff -i "$out/h264-720p-aac.mp4" -c copy "$out/h264-aac.mkv"

echo "VP9 and Opus in WebM"
ff -f lavfi -t 6 -i testsrc2=size=1280x720:rate=30 -f lavfi -t 6 -i sine=frequency=440:sample_rate=48000 \
  -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -crf 34 -b:v 0 -pix_fmt yuv420p -c:a libopus -b:a 96k -ac 2 \
  "$out/vp9-opus.webm"

echo "AV1"
ff -f lavfi -t 5 -i testsrc2=size=1280x720:rate=30 -f lavfi -t 5 -i sine=frequency=440:sample_rate=48000 \
  -c:v libsvtav1 -preset 10 -crf 40 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/av1-720p.mp4"

echo "HDR10, PQ"
ff -f lavfi -t 5 -i testsrc2=size=1920x1080:rate=30 \
  -c:v libx265 -preset medium -crf 26 -pix_fmt yuv420p10le -tag:v hvc1 \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc \
  -x265-params "hdr-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,1):max-cll=1000,400" \
  -movflags +faststart "$out/hevc-hdr10-pq.mp4"

echo "HLG"
ff -f lavfi -t 5 -i testsrc2=size=1920x1080:rate=30 \
  -c:v libx265 -preset medium -crf 26 -pix_fmt yuv420p10le -tag:v hvc1 \
  -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc \
  -x265-params "repeat-headers=1:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc" \
  -movflags +faststart "$out/hevc-hlg.mp4"

echo "Tears of Steel, ten seconds (CC BY 3.0, Blender Foundation)"
ff -ss 00:05:30 -t 10 -i https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov \
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/tears-of-steel-720p.mp4"

echo "Orion looking back at Earth (NASA, public domain)"
ff -t 10 -i https://images-assets.nasa.gov/video/jsc2022m000270_Orion_First_Imagery_Timelapse_and_Original_221117/jsc2022m000270_Orion_First_Imagery_Timelapse_and_Original_221117~medium.mp4 \
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -ac 2 \
  -movflags +faststart "$out/nasa-orion-earth.mp4"

echo
echo "Built:"
ls -la "$out"
