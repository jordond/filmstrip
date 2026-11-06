import { describe, expect, test } from 'bun:test'
import { demoUrl } from './demo'

describe('demoUrl', () => {
  test('asks the app to sit inside the television', () => {
    expect(demoUrl('/demo/app/')).toBe('/demo/app/?embed=tv')
  })

  test('keeps a query the source already has', () => {
    expect(demoUrl('/demo/app/?x=1')).toBe('/demo/app/?x=1&embed=tv')
  })
})
