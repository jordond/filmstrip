/**
 * Reads the value of a custom property out of the text of `src/styles/tokens.css`, for the few places that need a
 * colour outside CSS, like a `theme-color` meta tag. A missing token fails the build.
 */
export function tokenValue(tokensCss: string, name: string): string {
  const value = new RegExp(`--${name}:\\s*([^;]+);`).exec(tokensCss)?.[1]
  if (value === undefined) throw new Error(`No token --${name} in tokens.css`)
  return value.trim()
}
