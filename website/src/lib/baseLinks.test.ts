import { expect, test } from 'bun:test'
import { basedUrl } from './baseLinks.ts'

test('a root path moves under the base, fragment and query kept', () => {
  expect(basedUrl('/guides/edits/#trim', '/dev')).toBe('/dev/guides/edits/#trim')
  expect(basedUrl('/api/?q=1', '/0.1')).toBe('/0.1/api/?q=1')
})

test('anything that is not a root path is left alone', () => {
  for (const url of ['https://example.com/a', '//example.com/a', '#trim', '../edits/', 'edits/', 'mailto:a@b.c']) {
    expect(basedUrl(url, '/dev')).toBe(url)
  }
})

test('a path already under the base is left alone', () => {
  expect(basedUrl('/dev/guides/', '/dev')).toBe('/dev/guides/')
  expect(basedUrl('/dev', '/dev')).toBe('/dev')
  expect(basedUrl('/developer/', '/dev')).toBe('/dev/developer/')
})
