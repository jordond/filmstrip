import { describe, expect, test } from 'bun:test'
import { clipSpan, exportKey, NO_STEPS, noteOf, overlayOf, playheadAt, tvFrame, WAITING, writeText } from './beachTv'
import { timecode } from './heroTimeline'
import type { Status } from './tryProtocol'

const up: Status = { ...WAITING, durationMs: 12000 }
const trimmed: Status = { ...up, trimRange: { startMs: 2000, endMs: 8000 } }

describe('playheadAt', () => {
  test('reads the middle of the clip', () => {
    expect(playheadAt(5250, 12000)).toBeCloseTo(43.75)
  })

  test('stays on the lanes past either end', () => {
    expect(playheadAt(-1, 12000)).toBe(0)
    expect(playheadAt(13000, 12000)).toBe(100)
  })
})

describe('clipSpan', () => {
  test('is the whole lane with no trim', () => {
    expect(clipSpan(up)).toEqual({ left: 0, width: 100 })
  })

  test('is the reported range, whatever the app reports', () => {
    expect(clipSpan({ ...up, trimRange: { startMs: 3000, endMs: 6000 } })).toEqual({ left: 25, width: 25 })
    const span = clipSpan(trimmed)
    expect(span.left).toBeCloseTo(16.667, 2)
    expect(span.width).toBeCloseTo(50, 5)
  })

  test('is the whole lane until the clip length is known', () => {
    expect(clipSpan({ ...WAITING, trimRange: { startMs: 2000, endMs: 8000 } })).toEqual({ left: 0, width: 100 })
  })
})

describe('tvFrame', () => {
  test('opens on the raw clip across the whole lane', () => {
    const frame = tvFrame(WAITING, NO_STEPS)
    expect(frame.mode).toBe('EDIT')
    expect(frame.step).toBe('IMPORT  beach.mp4')
    expect(frame.clip).toEqual({ left: 0, width: 100 })
    expect(frame.grade).toBeNull()
    expect(frame.text).toBeNull()
    expect(frame.export.width).toBe(0)
    expect(frame.playhead).toBe(0)
  })

  test('puts the playhead and the timecode at source time', () => {
    const frame = tvFrame({ ...trimmed, positionMs: 5250 }, NO_STEPS)
    expect(frame.playhead).toBeCloseTo(43.75)
    expect(frame.timecode).toBe(timecode(5.25))
  })

  test('grade and title fill their lanes along the clip block', () => {
    const frame = tvFrame(trimmed, { trim: true, grade: true, title: true })
    expect(frame.grade).toEqual(frame.clip)
    expect(frame.text).toEqual(frame.clip)
    expect(frame.graded).toBe(true)
    expect(frame.showTitle).toBe(true)
  })

  test('says what is on, the trim in the numbers the app sent', () => {
    expect(tvFrame(up, NO_STEPS).step).toBe('RAW  beach.mp4')
    expect(tvFrame({ ...up, trimRange: { startMs: 3000, endMs: 75000 } }, NO_STEPS).step).toBe('TRIM 0:03 TO 1:15')
    expect(tvFrame(trimmed, { trim: true, grade: true, title: false }).step).toBe('TRIM 0:02 TO 0:08 + GRADE')
    expect(tvFrame(up, { trim: false, grade: true, title: true }).step).toBe('GRADE + TITLE')
  })

  test('fills the export lane with the real fraction, mid run', () => {
    const frame = tvFrame({ ...trimmed, export: { state: 'running', fraction: 0.37 } }, NO_STEPS)
    expect(frame.mode).toBe('REC ●')
    expect(frame.step).toBe('EXPORT  37%')
    expect(frame.export.left).toBeCloseTo(16.667, 2)
    expect(frame.export.width).toBeCloseTo(18.5, 5)
  })

  test('a finished export fills the lane along the clip block', () => {
    const frame = tvFrame({ ...up, export: { state: 'done' } }, NO_STEPS)
    expect(frame.step).toBe('EXPORT  DONE')
    expect(frame.export).toEqual({ left: 0, width: 100 })
  })
})

describe('exportKey', () => {
  test('is off until the clip is up', () => {
    expect(exportKey(WAITING).disabled).toBe(true)
  })

  test('writes when idle, cancels mid run and saves when done', () => {
    expect(exportKey(up)).toEqual({ label: 'EXPORT', disabled: false, control: { name: 'export' } })
    expect(exportKey({ ...up, export: { state: 'running', fraction: 0.37 } })).toEqual({
      label: 'CANCEL',
      disabled: false,
      control: { name: 'cancel' },
    })
    expect(exportKey({ ...up, export: { state: 'done' } })).toEqual({
      label: 'SAVE',
      disabled: false,
      control: { name: 'save' },
    })
  })

  test('is off with no encoder while the preview stays up', () => {
    const refused = { ...up, exportRefusal: 'No encoder.' }
    expect(exportKey(refused).disabled).toBe(true)
    expect(overlayOf(true, refused)).toBe('none')
    expect(noteOf(refused)).toBe('No encoder.')
  })
})

describe('overlayOf', () => {
  test('a picture the browser stopped asks for a tap', () => {
    expect(overlayOf(true, { ...up, awaitingTap: true })).toBe('tap')
  })

  test('offers LOAD before the press', () => {
    expect(overlayOf(false, WAITING)).toBe('offer')
  })

  test('says loading after the press', () => {
    expect(overlayOf(true, WAITING)).toBe('loading')
  })

  test('offers LOAD again after a failed load', () => {
    expect(overlayOf(true, { ...WAITING, error: 'The download answered HTTP 503.' })).toBe('offer')
  })

  test('leaves the picture alone when it is up, even with an error to show', () => {
    expect(overlayOf(true, { ...up, error: 'A frame this large is refused.' })).toBe('none')
  })
})

describe('noteOf', () => {
  test('prefers what went wrong over the refusal, and says nothing with neither', () => {
    expect(noteOf({ ...up, error: 'Failed.', exportRefusal: 'No encoder.' })).toBe('Failed.')
    expect(noteOf(up)).toBe('')
  })
})

describe('writeText', () => {
  test('leaves a node that already reads the text alone', () => {
    let writes = 0
    const node = {
      get textContent() {
        return 'same'
      },
      set textContent(_: string) {
        writes++
      },
    }
    writeText(node, 'same')
    expect(writes).toBe(0)
    writeText(node, 'other')
    expect(writes).toBe(1)
  })
})
