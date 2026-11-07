/**
 * What the beach TV draws. The hero runs it from `heroTimeline`, and the Try it TV from what the app reports, so the
 * numbers on the lanes here only ever come from a status.
 */

import { type HeroFrame, type Span, timecode } from './heroTimeline'
import type { Control, Status, Steps } from './tryProtocol'

/** Every step off, which is what the Try it TV opens on. */
export const NO_STEPS: Steps = { trim: false, grade: false, title: false }

/** What the Try it TV draws before the app has said anything. */
export const WAITING: Status = {
  type: 'status',
  positionMs: 0,
  durationMs: null,
  trimRange: null,
  export: { state: 'idle' },
  exportRefusal: null,
  error: null,
  awaitingTap: false,
}

/** What the Try it TV says when the app itself would not start. */
export const APP_FAILED = 'The app could not start in this browser.'

const CLIP = 'beach.mp4'

const pad = (n: number) => String(n).padStart(2, '0')

const minutes = (ms: number) => `${Math.floor(ms / 60000)}:${pad(Math.floor(ms / 1000) % 60)}`

/** Where a position of [positionMs] sits along a clip [durationMs] long, as a percentage of the lanes' width. */
export function playheadAt(positionMs: number, durationMs: number): number {
  return Math.max(0, Math.min(100, (100 * positionMs) / durationMs))
}

/** The clip block for [status], the whole lane until a trim is reported. */
export function clipSpan(status: Status): Span {
  const { durationMs, trimRange } = status
  if (durationMs === null || trimRange === null) return { left: 0, width: 100 }
  const left = playheadAt(trimRange.startMs, durationMs)
  return { left, width: playheadAt(trimRange.endMs, durationMs) - left }
}

function stepLine(status: Status, steps: Steps): string {
  if (status.export.state === 'running') return `EXPORT  ${Math.round(status.export.fraction * 100)}%`
  if (status.export.state === 'done') return 'EXPORT  DONE'
  if (status.durationMs === null) return `IMPORT  ${CLIP}`
  const on = [
    status.trimRange && `TRIM ${minutes(status.trimRange.startMs)} TO ${minutes(status.trimRange.endMs)}`,
    steps.grade && 'GRADE',
    steps.title && 'TITLE',
  ].filter(Boolean)
  return on.length === 0 ? `RAW  ${CLIP}` : on.join(' + ')
}

/** Everything the Try it TV draws for [status] with [steps] on. */
export function tvFrame(status: Status, steps: Steps): HeroFrame {
  const clip = clipSpan(status)
  const fraction = status.export.state === 'running' ? status.export.fraction : status.export.state === 'done' ? 1 : 0
  let mode = 'EDIT'
  if (status.export.state === 'running') mode = 'REC ●'
  else if (status.durationMs !== null) mode = 'PLAY ▸'
  return {
    mode,
    step: stepLine(status, steps),
    timecode: timecode(status.positionMs / 1000),
    graded: steps.grade,
    showTitle: steps.title,
    clip,
    grade: steps.grade ? clip : null,
    text: steps.title ? clip : null,
    export: { left: clip.left, width: clip.width * fraction },
    playhead: status.durationMs === null ? 0 : playheadAt(status.positionMs, status.durationMs),
  }
}

/** What the EXPORT key reads for [status], whether it can be pressed, and what a press sends. */
export function exportKey(status: Status): { label: string; disabled: boolean; control: Control } {
  if (status.export.state === 'running') return { label: 'CANCEL', disabled: false, control: { name: 'cancel' } }
  if (status.export.state === 'done') return { label: 'SAVE', disabled: false, control: { name: 'save' } }
  return {
    label: 'EXPORT',
    disabled: status.durationMs === null || status.exportRefusal !== null,
    control: { name: 'export' },
  }
}

/**
 * What covers the picture: LOAD, a loading note, a note asking for a tap on a picture the browser stopped, or nothing
 * once the clip is up and running. [pressed] is whether LOAD has been.
 */
export function overlayOf(pressed: boolean, status: Status): 'offer' | 'loading' | 'tap' | 'none' {
  if (status.durationMs !== null) return status.awaitingTap ? 'tap' : 'none'
  return pressed && status.error === null ? 'loading' : 'offer'
}

/** What the line under the keys says for [status]: what went wrong, otherwise why this device can't export. */
export function noteOf(status: Status): string {
  return status.error ?? status.exportRefusal ?? ''
}

/** A node whose text a status rewrites. */
export interface TextNode {
  textContent: string | null
}

/** Puts [text] on [node], leaving the node alone when it reads that already so a live region stays quiet. */
export function writeText(node: TextNode, text: string): void {
  if (node.textContent !== text) node.textContent = text
}

/** The parts of the beach TV a frame moves. */
export interface TvParts {
  mode: HTMLElement
  step: HTMLElement
  timecode: HTMLElement
  scene: HTMLElement
  title: HTMLElement
  clip: HTMLElement
  grade: HTMLElement
  text: HTMLElement
  export: HTMLElement
  playhead: HTMLElement
  well: HTMLElement
}

/** Finds the parts of the beach TV inside [root]. */
export function tvParts(root: ParentNode): TvParts {
  const part = (name: string) => root.querySelector<HTMLElement>(`[data-tv="${name}"]`)!
  return {
    mode: part('mode'),
    step: part('step'),
    timecode: part('timecode'),
    scene: part('scene'),
    title: part('title'),
    clip: part('clip'),
    grade: part('grade'),
    text: part('text'),
    export: part('export'),
    playhead: part('playhead'),
    well: part('well'),
  }
}

function place(element: HTMLElement, span: Span | null) {
  element.style.setProperty('--left', `${span?.left ?? 0}%`)
  element.style.setProperty('--width', `${span?.width ?? 0}%`)
}

/** Draws [frame] on [parts]. */
export function drawFrame(parts: TvParts, frame: HeroFrame): void {
  writeText(parts.mode, frame.mode)
  writeText(parts.step, frame.step)
  writeText(parts.timecode, frame.timecode)
  parts.scene.classList.toggle('graded', frame.graded)
  parts.title.classList.toggle('shown', frame.showTitle)
  place(parts.clip, frame.clip)
  place(parts.grade, frame.grade)
  place(parts.text, frame.text)
  place(parts.export, frame.export)
  parts.playhead.style.setProperty('--at', `${frame.playhead}%`)
}
