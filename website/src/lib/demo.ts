/** Where the wasm sample's index.html is served from. */
export const DEMO_SRC = '/demo/app/'

/** The sample's download size, shown before the reader chooses to load it. */
export const DEMO_SIZE = 'about 16 MB'

/** Where the Try it app's index.html is served from. */
export const TRY_SRC = '/demo/try/'

/** Where the docs playground's index.html is served from. A page picks its sample with `?sample=`. */
export const PLAYGROUND_SRC = '/demo/playground/'

/** The address of the playground running [sample]. */
export function playgroundUrl(sample: string): string {
  return `${PLAYGROUND_SRC}?sample=${encodeURIComponent(sample)}`
}

/** The address of the sample at [src], told it is running inside the site's television. */
export function demoUrl(src: string): string {
  return `${src}${src.includes('?') ? '&' : '?'}embed=tv`
}

/**
 * Builds the iframe that runs the wasm sample from [src]. [onLoad] runs once the iframe's document has loaded.
 * Nothing downloads until the caller puts the iframe in the page.
 */
export function demoIframe(src: string, onLoad?: () => void): HTMLIFrameElement {
  const iframe = document.createElement('iframe')
  iframe.src = demoUrl(src)
  iframe.title = 'filmstrip sample'
  iframe.allow = 'fullscreen'
  if (onLoad) iframe.addEventListener('load', onLoad, { once: true })
  return iframe
}

/** How much of filmstrip this browser can run. Preview needs `VideoDecoder`, and export needs `VideoEncoder` as well. */
export type WebCodecsSupport = 'full' | 'previewOnly' | 'none'

/** What [globals], the page's global scope, carries of WebCodecs. */
export function webCodecsSupport(globals: object): WebCodecsSupport {
  if (!('VideoDecoder' in globals)) return 'none'
  return 'VideoEncoder' in globals ? 'full' : 'previewOnly'
}

/** Whether the sample can run under [support]. When it can't, nothing builds its iframe and nothing downloads. */
export function canLoadDemo(support: WebCodecsSupport): boolean {
  return support !== 'none'
}

/** The part of an element [gateLoad] switches, typed as the DOM types it. */
export interface Hideable {
  hidden: boolean | string
}

/** What a television swaps between when the sample can't run. */
export interface DemoPanels {
  /** What offers LOAD. */
  offer: Hideable
  /** The note that takes its place. */
  noWebCodecs: Hideable
}

/**
 * Runs [arm], which wires up LOAD, when the sample can run under [support]. When it can't, [panels] swap LOAD for the
 * note and [arm] never runs, so nothing can build the iframe.
 */
export function gateLoad(support: WebCodecsSupport, panels: DemoPanels, arm: () => void): void {
  if (canLoadDemo(support)) {
    arm()
    return
  }
  panels.offer.hidden = true
  panels.noWebCodecs.hidden = false
}
