import { describe, expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { repoRoot } from './snippets'
import {
  type AppMessage,
  type Control,
  connectFrame,
  decodeAppMessage,
  encodeControl,
  MAX_MESSAGE_CHARS,
  messageFromFrame,
} from './tryProtocol'

const fixture = JSON.parse(readFileSync(join(repoRoot, 'sample/tryIt/protocol.fixture.json'), 'utf8'))
const appPage = readFileSync(join(repoRoot, 'sample/tryIt/src/wasmJsMain/resources/index.html'), 'utf8')

describe('the protocol fixture', () => {
  test('every control encodes to the entry the app decodes', () => {
    for (const entry of fixture.control) {
      const { type: _, ...control } = entry
      expect(JSON.parse(encodeControl(control as Control))).toEqual(entry)
      expect(encodeControl(control as Control)).toBe(JSON.stringify(entry))
    }
  })

  test('every status and ready the app encodes decodes whole', () => {
    for (const entry of [...fixture.status, ...fixture.ready]) {
      expect(decodeAppMessage(JSON.stringify(entry))).toEqual(entry)
    }
  })

  test('what the app page posts when the app will not start is the failure the fixture holds', () => {
    const [failed] = fixture.failed
    expect(decodeAppMessage(JSON.stringify(failed))).toEqual(failed)
    expect(appPage).toContain(`'${JSON.stringify(failed)}'`)
  })

  test('the app page reports an error from its own scripts and ignores one from any other origin', () => {
    const script = /<script>([\s\S]*?)<\/script>/.exec(appPage)?.[1] ?? ''
    const listeners: Record<string, (event: object) => void> = {}
    const posted: string[] = []
    new Function('addEventListener', 'parent', 'location', 'document', script)(
      (type: string, listener: (event: object) => void) => {
        listeners[type] = listener
      },
      { postMessage: (message: string) => posted.push(message) },
      { origin: 'https://site', href: 'https://site/demo/try/' },
      { fonts: { load: async () => {} } },
    )
    const reports = (filename: string, message = 'Uncaught Error') => {
      posted.length = 0
      listeners.error?.({ filename, message })
      return posted.length === 1
    }

    expect(reports('https://site/demo/try/try.js')).toBe(true)
    expect(reports('https://site/demo/try/abc.wasm')).toBe(true)
    expect(reports('wasm://wasm/abc')).toBe(true)
    expect(reports('')).toBe(true)
    expect(reports('chrome-extension://abc/boom.js')).toBe(false)
    expect(reports('moz-extension://abc/boom.js')).toBe(false)
    expect(reports('https://elsewhere/boom.js')).toBe(false)
    expect(reports('', 'Script error.')).toBe(false)
  })

  test('a status keeps its mid range position, trim and progress', () => {
    const status = decodeAppMessage(JSON.stringify(fixture.status[1]))
    expect(status).toMatchObject({
      positionMs: 5250,
      durationMs: 12000,
      trimRange: { startMs: 2000, endMs: 8000 },
      export: { state: 'running', fraction: 0.37 },
    })
  })

  test('a status keeps its export refusal, and one of the wrong type decodes to nothing', () => {
    const refused = fixture.status[3]
    expect(decodeAppMessage(JSON.stringify(refused))).toMatchObject({ exportRefusal: refused.exportRefusal })
    expect(decodeAppMessage(JSON.stringify({ ...refused, exportRefusal: 3 }))).toBeNull()
    const { exportRefusal: _, ...missing } = refused
    expect(decodeAppMessage(JSON.stringify(missing))).toBeNull()
  })

  test('a status keeps whether it awaits a tap, and one without it decodes to nothing', () => {
    const awaiting = fixture.status[4]
    expect(decodeAppMessage(JSON.stringify(awaiting))).toMatchObject({ awaitingTap: true })
    expect(decodeAppMessage(JSON.stringify({ ...awaiting, awaitingTap: 'yes' }))).toBeNull()
    const { awaitingTap: _, ...missing } = awaiting
    expect(decodeAppMessage(JSON.stringify(missing))).toBeNull()
  })

  test('a status with a bad trim or export decodes to nothing', () => {
    const status = fixture.status[0]
    expect(decodeAppMessage(JSON.stringify({ ...status, trimRange: { startMs: 'a', endMs: 1 } }))).toBeNull()
    expect(decodeAppMessage(JSON.stringify({ ...status, export: { state: 'running' } }))).toBeNull()
    expect(decodeAppMessage(JSON.stringify({ ...status, export: { state: 'bogus' } }))).toBeNull()
    expect(decodeAppMessage(JSON.stringify({ ...status, export: null }))).toBeNull()
  })

  test('a message past the limit decodes to nothing', () => {
    const entry = { ...fixture.status[0], error: 'a'.repeat(MAX_MESSAGE_CHARS) }
    expect(decodeAppMessage(JSON.stringify(entry))).toBeNull()
  })

  test('what the page does not know decodes to nothing', () => {
    for (const text of ['not json', 'null', '{"type":"bogus"}', '{"type":"status"}']) {
      expect(decodeAppMessage(text)).toBeNull()
    }
  })
})

describe('messageFromFrame', () => {
  const frame = {}
  const data = JSON.stringify(fixture.ready[0])

  test('takes a message from the frame on this origin', () => {
    expect(messageFromFrame({ origin: 'https://a', source: frame, data }, 'https://a', frame)).toEqual(fixture.ready[0])
  })

  test('drops another origin, another window and a non string', () => {
    expect(messageFromFrame({ origin: 'https://b', source: frame, data }, 'https://a', frame)).toBeNull()
    expect(messageFromFrame({ origin: 'https://a', source: {}, data }, 'https://a', frame)).toBeNull()
    expect(messageFromFrame({ origin: 'https://a', source: frame, data: {} }, 'https://a', frame)).toBeNull()
  })
})

describe('connectFrame', () => {
  const origin = 'https://a'
  const ready = JSON.stringify(fixture.ready[0])
  const status = JSON.stringify(fixture.status[0])

  function connected() {
    const posted: { text: string; origin: string }[] = []
    const frame = { postMessage: (text: string, target: string) => posted.push({ text, origin: target }) }
    type Listener = (event: { origin: string; source: unknown; data: unknown }) => void
    let listeners: Listener[] = []
    const heard: AppMessage[] = []
    const { send, close } = connectFrame({ contentWindow: frame }, (message) => heard.push(message), {
      location: { origin },
      addEventListener: (_, listener) => listeners.push(listener),
      removeEventListener: (_, listener) => {
        listeners = listeners.filter((it) => it !== listener)
      },
    })
    const fromApp = (data: string, from: string = origin, source: unknown = frame) =>
      listeners.forEach((listener) => {
        listener({ origin: from, source, data })
      })
    return { posted, heard, send, close, fromApp, sent: () => posted.map((it) => JSON.parse(it.text).name) }
  }

  test('holds controls until the app is ready, then sends them in order', () => {
    const { send, fromApp, sent } = connected()
    send({ name: 'steps', trim: true, grade: false, title: false })
    send({ name: 'export' })
    expect(sent()).toEqual([])

    fromApp(ready)

    expect(sent()).toEqual(['steps', 'export'])
  })

  test('sends a control straight away once the app is ready, on the page origin', () => {
    const { posted, send, fromApp, sent } = connected()
    fromApp(ready)
    send({ name: 'export' })
    expect(sent()).toEqual(['export'])
    expect(posted[0].origin).toBe(origin)
  })

  test('flushes once when the app says ready twice', () => {
    const { send, fromApp, sent } = connected()
    send({ name: 'export' })
    fromApp(ready)
    fromApp(ready)
    send({ name: 'cancel' })
    expect(sent()).toEqual(['export', 'cancel'])
  })

  test('a status does not open the gate and still reaches the page', () => {
    const { heard, send, fromApp, sent } = connected()
    send({ name: 'export' })
    fromApp(status)
    expect(sent()).toEqual([])
    expect(heard).toEqual([fixture.status[0]])
  })

  test('a failure reaches the page until the app is ready, and not after', () => {
    const failed = JSON.stringify(fixture.failed[0])
    const { heard, fromApp } = connected()
    fromApp(failed)
    expect(heard).toEqual(fixture.failed)

    fromApp(ready)
    fromApp(failed)
    expect(heard).toEqual([...fixture.failed, ...fixture.ready])
  })

  test('a closed link hears nothing more', () => {
    const { heard, close, fromApp } = connected()
    close()
    fromApp(ready)
    expect(heard).toEqual([])
  })

  test('a ready from another origin or window opens nothing', () => {
    const { heard, send, fromApp, sent } = connected()
    send({ name: 'export' })
    fromApp(ready, 'https://b')
    fromApp(ready, origin, {})
    expect(sent()).toEqual([])
    expect(heard).toEqual([])
  })
})
