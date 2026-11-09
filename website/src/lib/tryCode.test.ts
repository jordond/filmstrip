import { describe, expect, test } from 'bun:test'
import { NO_STEPS } from './beachTv'
import { readSnippet } from './snippets'
import { lineShown, readStepLines, stepsOfAttribute, untaggedCode } from './tryCode'

const code = `compositionOf {
  clip(source) {
    trim(2.seconds..8.seconds) // step: trim
  }
  // step: grade title
  effects {
    saturation(2f) // step: grade
  } // step: grade title
}`

const shownWith = (steps: typeof NO_STEPS) =>
  readStepLines(code)
    .filter((line) => lineShown(line, steps))
    .map((line) => line.text.trim())

describe('readStepLines', () => {
  test('takes the tag off and keeps the steps it names', () => {
    const lines = readStepLines(code)
    expect(lines[2]).toEqual({ text: '    trim(2.seconds..8.seconds)', steps: ['trim'] })
    expect(lines[4]).toEqual({ text: '  effects {', steps: ['grade', 'title'] })
    expect(lines[0]).toEqual({ text: 'compositionOf {', steps: [] })
  })

  test('gives a tag on its own line to the line below and drops it', () => {
    const lines = readStepLines(code)
    expect(lines).toHaveLength(8)
    expect(lines[3].text).toBe('  }')
    expect(lines[4]).toEqual({ text: '  effects {', steps: ['grade', 'title'] })
  })

  test('refuses a tag with no line below it', () => {
    expect(() => readStepLines('a()\n// step: trim')).toThrow('no line below')
  })

  test('leaves no tag in the code it hands the highlighter', () => {
    expect(untaggedCode(readStepLines(code))).not.toContain('step:')
  })

  test('refuses a step the keys do not know', () => {
    expect(() => readStepLines('fade() // step: fade')).toThrow('Unknown step "fade"')
  })
})

describe('lineShown', () => {
  test('shows only the base lines with every step off', () => {
    expect(shownWith(NO_STEPS)).toEqual(['compositionOf {', 'clip(source) {', '}', '}'])
  })

  test('shows a step and the block it sits in', () => {
    expect(shownWith({ ...NO_STEPS, grade: true })).toEqual([
      'compositionOf {',
      'clip(source) {',
      '}',
      'effects {',
      'saturation(2f)',
      '}',
      '}',
    ])
  })

  test('keeps a shared block while any of its steps is on', () => {
    expect(lineShown({ steps: ['grade', 'title'] }, { ...NO_STEPS, title: true })).toBe(true)
    expect(lineShown({ steps: ['grade', 'title'] }, NO_STEPS)).toBe(false)
  })

  test('shows a none line only while every step is off', () => {
    const hint = readStepLines('clip {\n  // Press TRIM // step: none\n}')[1]
    expect(hint).toEqual({ text: '  // Press TRIM', steps: ['none'] })
    expect(lineShown(hint, NO_STEPS)).toBe(true)
    expect(lineShown(hint, { ...NO_STEPS, grade: true })).toBe(false)
    expect(lineShown(hint, { trim: true, grade: true, title: true })).toBe(false)
  })

  test('shows a line tagged none and a step in both states', () => {
    expect(lineShown({ steps: ['none', 'trim'] }, NO_STEPS)).toBe(true)
    expect(lineShown({ steps: ['none', 'trim'] }, { ...NO_STEPS, trim: true })).toBe(true)
    expect(lineShown({ steps: ['none', 'trim'] }, { ...NO_STEPS, title: true })).toBe(false)
  })
})

describe('stepsOfAttribute', () => {
  test('reads the steps a line was tagged with', () => {
    expect(stepsOfAttribute('none')).toEqual(['none'])
    expect(stepsOfAttribute('grade title')).toEqual(['grade', 'title'])
    expect(stepsOfAttribute(undefined)).toEqual([])
    expect(stepsOfAttribute('grade bogus')).toEqual(['grade'])
  })
})

describe('the Try it sample', () => {
  const lines = readStepLines(readSnippet('commonMain/kotlin/dev/jordond/filmstrip/docs/landing/TryIt.kt', 'try-it'))

  test('tags every step at least once', () => {
    const tagged = new Set(lines.flatMap((line) => line.steps))
    expect([...tagged].sort()).toEqual(['grade', 'none', 'title', 'trim'])
  })

  test('shows only the hint inside the clip with every step off', () => {
    const shown = lines.filter((line) => lineShown(line, NO_STEPS)).map((line) => line.text.trim())
    expect(shown).toContain('// Press TRIM, GRADE or TITLE')
    expect(shown).not.toContain('trim(2.seconds..8.seconds)')
  })

  test('carries the edit the sample app runs', () => {
    const shown = untaggedCode(lines)
    expect(shown).toContain('trim(2.seconds..8.seconds)')
    expect(shown).toContain('saturation(2f)')
    expect(shown).toContain('contrast(1.15f)')
    expect(shown).toContain(`"SUMMER '96"`)
  })
})
