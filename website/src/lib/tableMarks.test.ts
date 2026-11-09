import { describe, expect, test } from 'bun:test'
import { markFor } from './tableMarks.ts'

const cell = (...children: { type: string; value: string }[]) => ({ type: 'element', tagName: 'td', children })
const text = (value: string) => ({ type: 'text', value })

describe('markFor', () => {
  test('marks a lone check or cross with its class and label', () => {
    expect(markFor(cell(text(' ✓ ')))).toMatchObject({
      tagName: 'span',
      properties: { className: ['mark-yes'], ariaLabel: 'Yes' },
      children: [{ value: '✓' }],
    })
    expect(markFor(cell(text('✗')))).toMatchObject({ properties: { className: ['mark-no'], ariaLabel: 'No' } })
  })

  test('leaves a cell with other words alone', () => {
    expect(markFor(cell(text('✓ from API 28')))).toBeNull()
    expect(markFor(cell(text('✓'), text('x')))).toBeNull()
    expect(markFor(cell())).toBeNull()
  })
})
