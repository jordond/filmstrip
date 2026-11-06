import { describe, expect, test } from 'bun:test'
import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { tokenValue } from './tokens'
import { vhsTheme } from './vhsTheme'

const src = join(import.meta.dir, '..')
const tokensCss = readFileSync(join(src, 'styles/tokens.css'), 'utf8')
const hex = /#[0-9a-fA-F]{3,8}\b/g

function sourceFiles(): string[] {
  return readdirSync(src, { recursive: true, encoding: 'utf8' })
    .filter((path) => /\.(astro|css|ts|mjs)$/.test(path))
    .filter((path) => !path.includes('.test.'))
    .map((path) => join(src, path))
}

describe('tokenValue', () => {
  test('reads a token by name', () => {
    expect(tokenValue(':root { --ground: #123456; --ground-deep: #654321; }', 'ground-deep')).toBe('#654321')
  })

  test('fails on a missing token', () => {
    expect(() => tokenValue(tokensCss, 'not-a-token')).toThrow('--not-a-token')
  })
})

function luminance(colour: string): number {
  const channels = [1, 3, 5].map((start) => Number.parseInt(colour.slice(start, start + 2), 16) / 255)
  const [r, g, b] = channels.map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!
}

function contrast(foreground: string, background: string): number {
  const [hi, lo] = [foreground, background].map((name) => luminance(tokenValue(tokensCss, name))).sort((a, b) => b - a)
  return (hi! + 0.05) / (lo! + 0.05)
}

// Every foreground and ground pair the docs and /api set text in.
const textPairs = [
  ['text', 'ground'],
  ['text', 'panel'],
  ['text', 'panel-raised'],
  ['muted-strong', 'ground'],
  ['muted-strong', 'ground-deep'],
  ['muted-strong', 'panel'],
  ['muted', 'ground'],
  ['muted', 'ground-deep'],
  ['muted', 'panel'],
  ['amber', 'ground'],
  ['amber', 'ground-deep'],
  ['amber', 'panel'],
  ['amber', 'panel-raised'],
  ['amber-hover', 'ground'],
  ['amber-hover', 'panel'],
  ['title-cream', 'ground'],
  ['cyan', 'panel'],
  ['coral', 'panel'],
  ['red', 'panel'],
  ['vcr-header', 'vcr-blue'],
  ['ground', 'amber'],
] as const

describe('text contrast', () => {
  for (const [foreground, background] of textPairs) {
    test(`${foreground} on ${background} clears 4.5:1`, () => {
      expect(contrast(foreground, background)).toBeGreaterThanOrEqual(4.5)
    })
  }
})

describe('tokens.css', () => {
  test('owns every hex colour apart from the Shiki theme', () => {
    const owners = new Set(['styles/tokens.css', 'lib/vhsTheme.ts'])
    const strays = sourceFiles()
      .filter((path) => !owners.has(relative(src, path)))
      .flatMap((path) => (readFileSync(path, 'utf8').match(hex) ?? []).map((colour) => `${relative(src, path)} ${colour}`))
    expect(strays).toEqual([])
  })

  test('holds every colour of the Shiki theme', () => {
    const theme = JSON.stringify(vhsTheme).match(hex) ?? []
    expect(theme.length).toBeGreaterThan(0)
    expect(theme.filter((colour) => !tokensCss.includes(colour))).toEqual([])
  })
})
