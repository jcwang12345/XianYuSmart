/** The one-time challenge URL is consumed only after an explicit user click. */
export function isOfficialItemVerificationUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:'
      && url.hostname === 'h5api.m.goofish.com'
      && !url.username
      && !url.password
      && (!url.port || url.port === '443')
      && !url.hash
      && [
        '/_____tmd_____/punish',
        '/h5/mtop.taobao.idle.pc.detail/1.0/_____tmd_____/punish',
      ].includes(url.pathname)
  } catch {
    return false
  }
}
