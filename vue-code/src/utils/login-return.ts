/** Preserve internal notification targets without allowing an external login redirect. */
export function loginReturnPath(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
      || /[\\\u0000-\u001f\u007f]/.test(value)) return '/dashboard'
  try {
    const url = new URL(value, 'https://local.invalid')
    if (url.origin !== 'https://local.invalid' || url.pathname === '/login') return '/dashboard'
    return url.pathname + url.search + url.hash
  } catch { return '/dashboard' }
}
