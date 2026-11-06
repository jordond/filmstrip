import { describe, expect, test } from 'bun:test'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { extractRegion, readSnippet } from './snippets'

const source = `package demo

fun outer() {
  // region found
  val a = 1
  // endregion

  if (true) {
    // region nested
    val b = 2
      val c = 3
    // endregion
  }

  val text = "// region quoted"
  // region after-string
  val d = text
  // endregion
}
`

describe('extractRegion', () => {
  test('returns the lines between the markers', () => {
    expect(extractRegion(source, 'found', 'Demo.kt')).toBe('val a = 1')
  })

  test('strips the indent the region lines share', () => {
    expect(extractRegion(source, 'nested', 'Demo.kt')).toBe('val b = 2\n  val c = 3')
  })

  test('throws naming the region and file when the region is missing', () => {
    expect(() => extractRegion(source, 'absent', 'Demo.kt')).toThrow(/region "absent".*Demo\.kt/)
  })

  test('ignores a marker inside a string literal', () => {
    expect(() => extractRegion(source, 'quoted', 'Demo.kt')).toThrow(/region "quoted"/)
    expect(extractRegion(source, 'after-string', 'Demo.kt')).toBe('val d = text')
  })
})

describe('readSnippet', () => {
  test('reads a region out of a file under the root', () => {
    const root = mkdtempSync(join(tmpdir(), 'snippets-'))
    writeFileSync(join(root, 'Demo.kt'), source)
    expect(readSnippet('Demo.kt', 'found', root)).toBe('val a = 1')
  })

  test('strips a leading internal from every line of the region', () => {
    const root = mkdtempSync(join(tmpdir(), 'snippets-'))
    writeFileSync(
      join(root, 'Internal.kt'),
      `// region api
internal data class Spec(val radius: Float) {
  internal val id = "internal value"
}

val internal = 1
// endregion
internal fun outside() = Unit
`,
    )
    expect(readSnippet('Internal.kt', 'api', root)).toBe(
      'data class Spec(val radius: Float) {\n  val id = "internal value"\n}\n\nval internal = 1',
    )
  })

  test('throws naming the file when it is missing', () => {
    const root = mkdtempSync(join(tmpdir(), 'snippets-'))
    expect(() => readSnippet('Missing.kt', 'found', root)).toThrow(/Missing\.kt/)
  })
})
