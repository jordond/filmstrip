import { describe, expect, test } from 'bun:test'
import { assetPath, DEMO_HOST, DEMO_PAGE, SITE_URL } from './hosts.ts'

describe('assetPath', () => {
  test('the demo host root serves the full screen demo', () => {
    expect(assetPath(new URL(DEMO_HOST))).toBe(DEMO_PAGE)
  })

  test('the demo host serves the sample and everything else as asked', () => {
    expect(assetPath(new URL('/demo/app/', DEMO_HOST))).toBe('/demo/app/')
  })

  test('the docs root stays the landing page', () => {
    expect(assetPath(new URL('/', SITE_URL))).toBe('/')
  })
})
