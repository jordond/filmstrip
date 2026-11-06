/** How many ticks one pass of the hero edit takes. */
export const HERO_TICKS = 180

/** How long one tick lasts, in milliseconds. */
export const HERO_TICK_MS = 100

/** The tick shown when the reader asks for reduced motion, with every step of the edit applied. */
export const HERO_STILL_TICK = 160

/** A block on the hero timeline, as percentages of the timeline's width. */
export interface Span {
  left: number
  width: number
}

/** Everything the hero TV draws at one tick. */
export interface HeroFrame {
  mode: string
  step: string
  timecode: string
  graded: boolean
  showTitle: boolean
  clip: Span
  grade: Span | null
  text: Span | null
  export: Span
  playhead: number
}

const progress = (t: number, from: number, to: number) => Math.max(0, Math.min(1, (t - from) / (to - from)))

const pad = (n: number) => String(n).padStart(2, '0')

/**
 * Formats [seconds] as an `HH:MM:SS:FF` timecode at 30 frames a second.
 */
export function timecode(seconds: number): string {
  return `00:00:${pad(Math.floor(seconds) % 60)}:${pad(Math.floor((seconds % 1) * 30))}`
}

/**
 * Works out what the hero TV shows at tick [t] of its loop. Ticks outside the loop wrap.
 *
 * The edit runs import, trim, grade, text overlay, export, then plays the trimmed range back. The trim keeps 15% to
 * 80% of the clip, and the playhead's position maps onto a ten second source for the timecode.
 */
export function heroFrame(t: number): HeroFrame {
  const tick = ((Math.floor(t) % HERO_TICKS) + HERO_TICKS) % HERO_TICKS
  const trim = progress(tick, 30, 50)
  const left = 15 * trim
  const right = 100 - 20 * trim
  const width = right - left
  const exported = progress(tick, 110, 150)

  let playhead: number
  if (tick < 30) playhead = (100 * tick) / 30
  else if (tick < 55) playhead = left
  else if (tick < 110) playhead = left + width * progress(tick, 55, 110)
  else if (tick < 150) playhead = left + width * exported
  else playhead = left + width * progress(tick, 150, 180)

  let mode = 'EDIT'
  let step: string
  if (tick < 30) step = 'IMPORT  beach.mp4'
  else if (tick < 55) step = 'TRIM  0:02 TO 0:08'
  else if (tick < 80) step = 'EFFECT  COLOUR GRADE'
  else if (tick < 110) step = 'OVERLAY  TEXT'
  else if (tick < 150) {
    step = `EXPORT  ${Math.round(exported * 100)}%`
    mode = 'REC ●'
  } else {
    step = 'WROTE  clip.mp4'
    mode = 'PLAY ▸'
  }

  return {
    mode,
    step,
    timecode: timecode((playhead / 100) * 10),
    graded: tick >= 58,
    showTitle: tick >= 92,
    clip: { left, width },
    grade: tick >= 58 ? { left, width: width * progress(tick, 58, 72) } : null,
    text: tick >= 85 ? { left: left + width * 0.2, width: width * 0.55 * progress(tick, 85, 98) } : null,
    export: { left, width: tick >= 110 ? width * exported : 0 },
    playhead,
  }
}
