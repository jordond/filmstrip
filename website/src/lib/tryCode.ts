import type { Steps } from './tryProtocol'

/** One edit step the Try it keys switch. */
export type Step = keyof Steps

/** A name a `// step:` tag may use, an edit step or `none` for the lines that show while every key is off. */
export type StepTag = Step | 'none'

const STEPS: readonly Step[] = ['trim', 'grade', 'title']

const TAGS: readonly StepTag[] = [...STEPS, 'none']

/** A trailing `// step: trim grade` tag on a line of the Try it sample. */
const STEP_TAG = /\s*\/\/\s*step:\s*(.*)$/

/**
 * A line of the Try it code with its step tag taken off. A line with no [steps] always shows, the rest show while any
 * of their steps is on. The `none` tag counts as on while every step is off.
 */
export interface StepLine {
  text: string
  steps: StepTag[]
}

/**
 * Splits a Try it snippet into lines and reads the `// step:` tag off each. A tag at the end of a line tags that line,
 * and a tag on a line of its own tags the line below it, for the places the formatter won't keep a trailing comment.
 * Throws on a tag naming no step the keys know, so a typo in the Kotlin fails the build rather than hiding a line.
 */
export function readStepLines(code: string): StepLine[] {
  const lines: StepLine[] = []
  let pending: StepTag[] = []
  for (const line of code.split('\n')) {
    const tag = STEP_TAG.exec(line)
    const names = tag ? stepNames(tag[1], line) : []
    const text = tag ? line.slice(0, tag.index).trimEnd() : line
    if (tag && text.trim() === '') {
      pending = names
      continue
    }
    lines.push({ text, steps: [...new Set([...pending, ...names])] })
    pending = []
  }
  if (pending.length > 0) throw new Error(`A step tag ends the snippet with no line below it`)
  return lines
}

function stepNames(tag: string, line: string): StepTag[] {
  const names = tag.trim().split(/\s+/)
  for (const name of names) {
    if (!TAGS.includes(name as StepTag)) throw new Error(`Unknown step "${name}" on line: ${line}`)
  }
  return names as StepTag[]
}

/** The snippet as plain Kotlin, every tag taken off. */
export function untaggedCode(lines: StepLine[]): string {
  return lines.map((line) => line.text).join('\n')
}

/** Whether [line] shows while [steps] are on. */
export function lineShown(line: Pick<StepLine, 'steps'>, steps: Steps): boolean {
  if (line.steps.length === 0) return true
  const noneOn = STEPS.every((step) => !steps[step])
  return line.steps.some((tag) => (tag === 'none' ? noneOn : steps[tag]))
}

/**
 * Reads a line's tags back from the space separated `data-step` attribute the page writes, ignoring anything that
 * isn't a tag.
 */
export function stepsOfAttribute(value: string | undefined): StepTag[] {
  return (value ?? '').split(/\s+/).filter((name): name is StepTag => TAGS.includes(name as StepTag))
}

/** The CustomEvent the Try it TV fires on its root whenever a key changes the steps. */
export const STEPS_EVENT = 'try:steps'
