import { describe, expect, test } from 'bun:test'
import { HERO_STILL_TICK, HERO_TICKS, heroFrame, timecode } from './heroTimeline'

describe('heroFrame', () => {
  test('imports the raw clip with the playhead sweeping the whole timeline', () => {
    const frame = heroFrame(15)
    expect(frame.step).toBe('IMPORT  beach.mp4')
    expect(frame.mode).toBe('EDIT')
    expect(frame.graded).toBe(false)
    expect(frame.clip).toEqual({ left: 0, width: 100 })
    expect(frame.playhead).toBe(50)
  })

  test('slides the clip ends in linearly during the trim', () => {
    const frame = heroFrame(40)
    expect(frame.step).toBe('TRIM  0:02 TO 0:08')
    expect(frame.clip.left).toBeCloseTo(7.5)
    expect(frame.clip.width).toBeCloseTo(82.5)
    expect(frame.playhead).toBeCloseTo(7.5)
  })

  test('keeps 15% to 80% once trimmed', () => {
    expect(heroFrame(60).clip).toEqual({ left: 15, width: 65 })
  })

  test('grows the grade bar and switches to the graded scene', () => {
    const frame = heroFrame(65)
    expect(frame.step).toBe('EFFECT  COLOUR GRADE')
    expect(frame.graded).toBe(true)
    expect(frame.grade?.width).toBeCloseTo(32.5)
    expect(heroFrame(57).grade).toBeNull()
  })

  test('grows the text bar and shows the title from tick 92', () => {
    expect(heroFrame(91).showTitle).toBe(false)
    const frame = heroFrame(92)
    expect(frame.step).toBe('OVERLAY  TEXT')
    expect(frame.showTitle).toBe(true)
    expect(frame.text?.left).toBeCloseTo(28)
    expect(frame.text?.width).toBeCloseTo(65 * 0.55 * (7 / 13))
  })

  test('records with the export bar and percent tracking each other', () => {
    const frame = heroFrame(130)
    expect(frame.mode).toBe('REC ●')
    expect(frame.step).toBe('EXPORT  50%')
    expect(frame.export).toEqual({ left: 15, width: 32.5 })
    expect(frame.playhead).toBeCloseTo(47.5)
  })

  test('plays back the trimmed range after writing the file', () => {
    const frame = heroFrame(165)
    expect(frame.mode).toBe('PLAY ▸')
    expect(frame.step).toBe('WROTE  clip.mp4')
    expect(frame.playhead).toBeCloseTo(47.5)
    expect(frame.timecode).toBe('00:00:04:22')
  })

  test('wraps ticks outside the loop', () => {
    expect(heroFrame(HERO_TICKS + 40)).toEqual(heroFrame(40))
    expect(heroFrame(-20)).toEqual(heroFrame(HERO_TICKS - 20))
  })

  test('the reduced motion still has every step applied', () => {
    const frame = heroFrame(HERO_STILL_TICK)
    expect(frame.graded).toBe(true)
    expect(frame.showTitle).toBe(true)
    expect(frame.grade).not.toBeNull()
    expect(frame.text).not.toBeNull()
  })
})

describe('timecode', () => {
  test('counts frames at 30 a second', () => {
    expect(timecode(2.5)).toBe('00:00:02:15')
    expect(timecode(0)).toBe('00:00:00:00')
  })
})
