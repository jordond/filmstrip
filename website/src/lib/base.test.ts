import { expect, test } from 'bun:test'
import { joinBase } from './base.ts'

test('joinBase puts a path under the base, with or without its trailing slash', () => {
  expect(joinBase('/dev', '/guides/edits/')).toBe('/dev/guides/edits/')
  expect(joinBase('/0.1/', '/api/')).toBe('/0.1/api/')
  expect(joinBase('', '/api/')).toBe('/api/')
  expect(joinBase('/', '/api/')).toBe('/api/')
})
