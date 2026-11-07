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

# The palm from the landing page hero, as SVG path data with its tokens spaced out. The last subpath is a crown that
# covers where the fronds taper to points.
palm_path="M 52 120 C 50 95 49 75 54 52 L 57 52 C 53 75 55 95 58 120 Z \
M 55 52 C 40 40 22 42 8 52 C 24 40 40 38 55 50 Z \
M 55 52 C 66 36 84 34 96 42 C 82 36 66 40 56 50 Z \
M 55 50 C 50 34 38 24 24 22 C 40 28 50 38 56 49 Z \
M 56 50 C 64 32 76 24 90 24 C 76 30 66 38 57 50 Z \
M 55 52 C 58 62 70 70 82 72 C 70 66 62 60 57 51 Z \
M 52 51 C 52 47 59 47 59 51 C 59 54 52 54 52 51 Z"

# Adds the crossing test for one polygon edge to `crossings`, in pixels at 12 per path unit.
palm_edge() {
  local ax=$(($1 * 3 / 16)) ay=$(($2 * 3 / 16)) bx=$(($3 * 3 / 16)) by=$(($4 * 3 / 16))
  if ((ay == by)); then return; fi
  crossings+="+gt(Y,$((ay < by ? ay : by)))*lte(Y,$((ay < by ? by : ay)))*lt(X,$ax+(Y-$ay)*($((bx - ax)))/($((by - ay))))"
}

# Prints a geq expression that is 255 inside the palm. Each subpath flattens to a polygon and fills by counting edge
# crossings to the left of the pixel. Coordinates stay in 64ths of a path unit while flattening.
palm_mask() {
  local fills="" crossings="" x=0 y=0 start_x=0 start_y=0 curve_x curve_y i u next_x next_y
  set -- $palm_path
  while (($#)); do
    case "$1" in
      M)
        x=$(($2 * 64)) y=$(($3 * 64)) start_x=$x start_y=$y crossings=""
        shift 3
        ;;
      L)
        palm_edge $x $y $(($2 * 64)) $(($3 * 64))
        x=$(($2 * 64)) y=$(($3 * 64))
        shift 3
        ;;
      C)
        curve_x=$x curve_y=$y
        for ((i = 1; i <= 12; i++)); do
          u=$((12 - i))
          next_x=$(((u * u * u * curve_x + 64 * (3 * u * u * i * $2 + 3 * u * i * i * $4 + i * i * i * $6)) / 1728))
          next_y=$(((u * u * u * curve_y + 64 * (3 * u * u * i * $3 + 3 * u * i * i * $5 + i * i * i * $7)) / 1728))
          palm_edge $x $y $next_x $next_y
          x=$next_x y=$next_y
        done
        shift 7
        ;;
      Z)
        palm_edge $x $y $start_x $start_y
        fills+="+mod(0$crossings,2)"
        shift
        ;;
    esac
  done
  printf 'gt(0%s,0)*255' "$fills"
}

# A one frame source the graph repeats for all 360 frames, for layers that only need drawing once.
still() { printf 'color=black:s=%s:r=30:d=0.033,format=%s,geq=%s,loop=loop=359:size=1' "$1" "$2" "$3"; }

# Prints the sky's value for one channel, given that channel at the hero's four gradient stops.
sky_channel() {
  printf "if(lt(Y,274),%s+(%s-%s)*Y/274,if(lt(Y,418),%s+(%s-%s)*(Y-274)/144,if(lt(Y,446),%s+(%s-%s)*(Y-418)/28,%s)))" \
    "$1" "$2" "$1" "$2" "$3" "$2" "$3" "$4" "$3" "$4"
}

# Prints the sea's value for one channel: its base colour under white shimmer under the orange bands. The bands scroll
# 16 pixels and the shimmer 80 in the 2.4 second period.
sea_channel() {
  local shimmer="0.12*(1-abs(mod(X-T*80/2.4,80)/40-1))"
  local band="0.55*clip(2.5-abs(mod(Y-T*16/2.4,16)-2),0,1)"
  printf "'((%s*(1-%s)+255*%s)*(1-%s)+%s*%s)'" "$1" "$shimmer" "$shimmer" "$band" "$2" "$band"
}

# The landing page hero's beach at sunset, in the hero's colours then graded flat. Every motion divides the 12 seconds
# and the audio fades its own tail into its head, so the clip loops without a jump. A little static noise keeps the flat
# sky from banding.
beach_sunset() {
  local swell="(0.25+0.45*pow((1-cos(2*PI*t/6))/2,2)+0.3*pow((1-cos(2*PI*(t+1)/4))/2,3))"
  local flat="clip(((val/255-0.5)*0.85+0.5)*1.08*255,0,255)"
  ff -filter_complex "
    $(still 1280x720 gbrp "r='$(sky_channel 43 138 255 255)':g='$(sky_channel 26 47 123 196)':b='$(sky_channel 92 107 74 107)'")[sky];
    $(still 360x360 gbrap "r=255:g='if(lt(mod(Y,22),16),226,178)':b='if(lt(mod(Y,22),16),122,74)':a='clip(180-hypot(X-179.5,Y-179.5),0,1)*255'")[sun];
    color=black:s=1280x288:r=30:d=2.4,format=gbrp,geq=r=$(sea_channel 58 255):g=$(sea_channel 29 170):b=$(sea_channel 94 90),loop=loop=4:size=72[sea];
    $(still 1200x1440 gray "lum='$(palm_mask)'"),dilation,dilation,dilation,scale=440:528:flags=area,pad=520:1056:18:0:black[mask];
    color=0x14081f:s=520x1056:r=30:d=12,format=gbrp[bark];
    [bark][mask]alphamerge,rotate=a='2*PI/180*(1-cos(2*PI*t/4))/2':c=none[palm];
    [sky][sun]overlay=x=460:y='130+11*(1-cos(2*PI*t/6))':eval=frame:format=gbrp[hero];
    [hero][sea]overlay=y=432:format=gbrp[hero];
    [hero][palm]overlay=x=745:y=202:format=gbrp,
    colorchannelmixer=rr=0.606:rg=0.358:rb=0.036:gr=0.106:gg=0.858:gb=0.036:br=0.106:bg=0.358:bb=0.536,
    lutrgb=r='$flat':g='$flat':b='$flat',
    scale=out_color_matrix=bt709:out_range=tv:flags=bicubic+accurate_rnd+full_chroma_int,format=yuv420p,noise=c0s=3:c0f=u:c1s=2:c1f=u:c2s=2:c2f=u:all_seed=1996,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709[video];
    anoisesrc=d=14:c=pink:r=48000:a=0.5:s=1996[left];
    anoisesrc=d=14:c=pink:r=48000:a=0.5:s=2096[right];
    [left][right]join=inputs=2:channel_layout=stereo,highpass=f=120,lowpass=f=3200,asplit[head][tail];
    [head]atrim=0:12,afade=in:d=2:curve=qsin[head];
    [tail]atrim=12:14,asetpts=PTS-STARTPTS,afade=out:d=2:curve=qsin[tail];
    [head][tail]amix=inputs=2:normalize=0:duration=first,aeval='0.9*val(0)*$swell|0.9*val(1)*$swell':c=same[audio]
  " -map "[video]" -map "[audio]" -t 12 \
    -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p \
    -c:a aac -b:a 96k -ac 2 \
    -movflags +faststart "$out/beach-sunset.mp4"
}

if [[ "${1:-}" == "beach-sunset" ]]; then
  beach_sunset
  exit 0
fi

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

echo "A beach at sunset, generated to look like the landing page hero"
beach_sunset

echo
echo "Built:"
ls -la "$out"
