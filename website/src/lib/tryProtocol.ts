/**
 * The page's end of the message channel to the Try it app. A message is a JSON string on `postMessage`, between two
 * windows on one origin. `sample/tryIt/protocol.fixture.json` holds this file and `TryProtocol.kt` to the same shapes.
 */

/** Which steps of the edit are on. */
export interface Steps {
  trim: boolean
  grade: boolean
  title: boolean
}

/** One change the page asks of the app. */
export type Control = ({ name: 'steps' } & Steps) | { name: 'export' } | { name: 'save' } | { name: 'cancel' } | { name: 'retry' }

/** The app is up. */
export interface Ready {
  type: 'ready'
}

/** The app's page could not get the app started. */
export interface Failed {
  type: 'failed'
}

/** Where an export stands. */
export type ExportReport = { state: 'idle' } | { state: 'running'; fraction: number } | { state: 'done' }

/** A part of the clip, in source time. */
export interface TrimRange {
  startMs: number
  endMs: number
}

/** What the app reports whenever any of it changes. */
export interface Status {
  type: 'status'
  /** Where the playhead is, in source time. */
  positionMs: number
  /** How long the clip is, or null until a player has it up. */
  durationMs: number | null
  /** The part of the clip the edit keeps, or null with no trim. */
  trimRange: TrimRange | null
  export: ExportReport
  /** Why this device can't export at all, or null when it can or hasn't answered yet. */
  exportRefusal: string | null
  error: string | null
  /** Whether the browser stopped playback and only a tap on the picture can start it again. */
  awaitingTap: boolean
}

export type AppMessage = Ready | Failed | Status

/** The text one control travels as. */
export function encodeControl(control: Control): string {
  return JSON.stringify({ type: 'control', ...control })
}

/** The longest message from the app this page parses, comfortably over what a status encodes to. */
export const MAX_MESSAGE_CHARS = 65536

function decodeRange(value: unknown): TrimRange | null | undefined {
  if (value === null) return null
  if (typeof value !== 'object') return undefined
  const { startMs, endMs } = value as Record<string, unknown>
  return typeof startMs === 'number' && typeof endMs === 'number' ? { startMs, endMs } : undefined
}

function decodeExport(value: unknown): ExportReport | undefined {
  if (typeof value !== 'object' || value === null) return undefined
  const { state, fraction } = value as Record<string, unknown>
  if (state === 'idle' || state === 'done') return { state }
  return state === 'running' && typeof fraction === 'number' ? { state, fraction } : undefined
}

/** Reads one message from the app, or null for anything this page does not know. */
export function decodeAppMessage(text: string): AppMessage | null {
  if (text.length > MAX_MESSAGE_CHARS) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const message = parsed as Record<string, unknown>
  if (message.type === 'ready') return { type: 'ready' }
  if (message.type === 'failed') return { type: 'failed' }
  if (message.type !== 'status') return null
  const { positionMs, durationMs, exportRefusal, error, awaitingTap } = message
  const trimRange = decodeRange(message.trimRange)
  const report = decodeExport(message.export)
  if (typeof positionMs !== 'number') return null
  if (durationMs !== null && typeof durationMs !== 'number') return null
  if (trimRange === undefined || report === undefined) return null
  if (exportRefusal !== null && typeof exportRefusal !== 'string') return null
  if (error !== null && typeof error !== 'string') return null
  if (typeof awaitingTap !== 'boolean') return null
  return { type: 'status', positionMs, durationMs, trimRange, export: report, exportRefusal, error, awaitingTap }
}

/** The parts of a `message` event this page checks. */
export interface FrameEvent {
  origin: string
  source: unknown
  data: unknown
}

/** The message in [event] when it came from [frame] on [origin], or null for anything else. */
export function messageFromFrame(event: FrameEvent, origin: string, frame: unknown): AppMessage | null {
  if (event.origin !== origin || event.source !== frame || typeof event.data !== 'string') return null
  return decodeAppMessage(event.data)
}

/** The parts of the page's window [connectFrame] uses. */
export interface FrameHost {
  location: { origin: string }
  addEventListener(type: 'message', listener: (event: FrameEvent) => void): void
  removeEventListener(type: 'message', listener: (event: FrameEvent) => void): void
}

/** The parts of an iframe [connectFrame] uses. */
export interface FrameElement {
  contentWindow: { postMessage(message: string, targetOrigin: string): void } | null
}

/** A connection to the app in one iframe. */
export interface FrameLink {
  /** Sends the app a control. */
  send(control: Control): void
  /** Stops listening, for an iframe on its way out. */
  close(): void
}

/**
 * Listens to the app in [iframe]. Controls sent before the app says it is ready wait and go in order, and a failure
 * only counts until then, since once the app is up it reports for itself. [host] is the window the page runs in.
 */
export function connectFrame(
  iframe: FrameElement,
  onMessage: (message: AppMessage) => void,
  host: FrameHost = window,
): FrameLink {
  const origin = host.location.origin
  const waiting: string[] = []
  let ready = false
  const listener = (event: FrameEvent) => {
    const message = messageFromFrame(event, origin, iframe.contentWindow)
    if (message === null) return
    if (message.type === 'failed' && ready) return
    if (message.type === 'ready' && !ready) {
      ready = true
      for (const text of waiting.splice(0)) iframe.contentWindow?.postMessage(text, origin)
    }
    onMessage(message)
  }
  host.addEventListener('message', listener)
  return {
    send(control) {
      const text = encodeControl(control)
      if (ready) iframe.contentWindow?.postMessage(text, origin)
      else waiting.push(text)
    },
    close: () => host.removeEventListener('message', listener),
  }
}
