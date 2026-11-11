import { expect, test } from 'bun:test'
import { DEMO_HOST, DEMO_PAGE, SITE_URL } from './hosts.ts'

test('the demo host is its own host under the site', () => {
  expect(new URL(DEMO_HOST).hostname.endsWith(`.${new URL(SITE_URL).hostname}`)).toBe(true)
})

test('the demo page is a path inside a version', () => {
  expect(DEMO_PAGE.startsWith('/')).toBe(false)
})
