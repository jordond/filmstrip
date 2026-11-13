import { describe, expect, test } from 'bun:test'
import { DEV, LATEST, siteVersions } from './docsVersions.ts'
import { menuOptions, versionTarget } from './versionMenu.ts'

const manifest = siteVersions(['0.1.0', '0.2.0', '0.3.1'])

describe('menuOptions', () => {
  test('lists every version, marks the latest and selects the current one', () => {
    expect(menuOptions(manifest, '0.2')).toEqual([
      { value: DEV, label: DEV, selected: false },
      { value: LATEST, label: '0.3 (latest)', selected: false },
      { value: '0.2', label: '0.2', selected: true },
      { value: '0.1', label: '0.1', selected: false },
    ])
  })

  test('a page under latest selects the latest stable version', () => {
    const options = menuOptions(manifest, LATEST)
    expect(options.filter((option) => option.selected)).toEqual([{ value: LATEST, label: '0.3 (latest)', selected: true }])
    expect(options).toHaveLength(4)
  })

  test('a version the manifest does not list yet goes on top, selected', () => {
    const options = menuOptions(manifest, '0.4')
    expect(options[0]).toEqual({ value: '0.4', label: '0.4', selected: true })
    expect(options.filter((option) => option.selected)).toHaveLength(1)
    expect(options).toHaveLength(5)
  })

  test('with no release the menu is dev alone', () => {
    expect(menuOptions(siteVersions([]), DEV)).toEqual([{ value: DEV, label: DEV, selected: true }])
  })
})

describe('versionTarget', () => {
  test('the same page when the other version has it', async () => {
    const asked: string[] = []
    const target = await versionTarget('/dev/guides/edits/', '0.2', async (path) => {
      asked.push(path)
      return true
    })
    expect(target).toBe('/0.2/guides/edits/')
    expect(asked).toEqual(['/0.2/guides/edits/'])
  })

  test('the front page when it does not', async () => {
    expect(await versionTarget('/dev/guides/shuttle/', '0.1', async () => false)).toBe('/0.1/')
  })

  test('latest is a segment like any other', async () => {
    expect(await versionTarget('/0.1/guides/edits/', LATEST, async () => true)).toBe('/latest/guides/edits/')
  })
})
