/** Where the wasm sample's index.html is served from. */
export const DEMO_SRC = '/demo/app/'

/** The sample's download size, shown before the reader chooses to load it. */
export const DEMO_SIZE = 'about 15 MB'

/**
 * Builds the iframe that runs the wasm sample from [src]. [onLoad] runs once the iframe's document has loaded.
 * Nothing downloads until the caller puts the iframe in the page.
 */
export function demoIframe(src: string, onLoad?: () => void): HTMLIFrameElement {
  const iframe = document.createElement('iframe')
  iframe.src = src
  iframe.title = 'filmstrip sample'
  iframe.allow = 'fullscreen'
  if (onLoad) iframe.addEventListener('load', onLoad, { once: true })
  return iframe
}
