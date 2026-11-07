import { describe, expect, test } from 'bun:test'
import { canLoadDemo, demoUrl, webCodecsSupport } from './demo'

describe('demoUrl', () => {
  test('asks the app to sit inside the television', () => {
    expect(demoUrl('/demo/app/')).toBe('/demo/app/?embed=tv')
  })

  test('keeps a query the source already has', () => {
    expect(demoUrl('/demo/app/?x=1')).toBe('/demo/app/?x=1&embed=tv')
  })
})

describe('webCodecsSupport', () => {
  const stub = () => {}

  test('reads full with a decoder and an encoder', () => {
    expect(webCodecsSupport({ VideoDecoder: stub, VideoEncoder: stub })).toBe('full')
  })

  test('reads preview only with a decoder and no encoder', () => {
    expect(webCodecsSupport({ VideoDecoder: stub })).toBe('previewOnly')
  })

  test('reads none with no decoder, whatever else is there', () => {
    expect(webCodecsSupport({})).toBe('none')
    expect(webCodecsSupport({ VideoEncoder: stub })).toBe('none')
  })
})

describe('canLoadDemo', () => {
  test('loads the sample in the states that can preview', () => {
    expect(canLoadDemo('full')).toBe(true)
    expect(canLoadDemo('previewOnly')).toBe(true)
  })

  test('builds no iframe with no WebCodecs', () => {
    expect(canLoadDemo('none')).toBe(false)
  })
})
